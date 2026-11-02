package app.companion.reader

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class ReaderActivity : AppCompatActivity() {
    private val api get() = (application as App).api
    private val prefs by lazy { getSharedPreferences("positions", MODE_PRIVATE) }
    private var fileId = 0L
    private var publication: Publication? = null
    private var lastPercent = -1.0
    // The position the book settled on after opening. Progress is only written once the reader moves
    // away from it, so merely opening a book never overwrites what the server has.
    private var openedAt: Double? = null
    private var moved = false
    // Pages turned since progress was last queued for upload; it goes up every PAGES_PER_SYNC pages.
    private var pagesSinceSync = 0
    private var lastSeenProgress: Double? = null
    private var navigator: EpubNavigatorFragment? = null
    private val readerSettings by lazy { ReaderSettings(this) }
    private var toolbar: View? = null
    private var chromeBars: ReaderChrome? = null

    // For "minutes left in chapter": words in each resource, and which resources open a chapter.
    private var wordsByResource: IntArray? = null
    private var chapterStartIndexes: List<Int> = emptyList()

    // Learning the reader's pace: words moved forward per second of active reading, this session.
    private var lastWordPosition: Double? = null
    private var lastSampleAt: Long? = null
    private var paceWords = 0.0
    private var paceSeconds = 0.0
    private var bookId = 0L
    private var highlights: HighlightSync? = null
    private var firstResume = true

    // Reading-time tracking: time between page turns counts only while it stays under IDLE_LIMIT_MS.
    private val sessionId = java.util.UUID.randomUUID().toString()
    private val startedAt = java.time.Instant.now()
    private var activeMs = 0L
    private var lastActivity: Long? = null
    private var startProgress: Double? = null
    private var currentProgress: Double? = null

    private fun touchActivity() {
        val now = android.os.SystemClock.elapsedRealtime()
        lastActivity?.let { activeMs += (now - it).coerceAtMost(IDLE_LIMIT_MS) }
        lastActivity = now
    }

    private suspend fun uploadSession() {
        val seconds = (activeMs / 1000).toInt()
        if (seconds < 5) return
        val end = currentProgress
        val session = ReadingSession(
            sessionId = sessionId,
            startedAt = startedAt.toString(),
            endedAt = java.time.Instant.now().toString(),
            durationSeconds = seconds,
            progressDelta = if (end != null && startProgress != null) end - startProgress!! else null,
            endProgress = end?.coerceIn(0.0, 100.0),
        )
        // Queued, not sent: it goes out when the server is reachable. The same id replaces its earlier copy.
        (application as App).outbox.session(fileId, kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(ReadingSession.serializer(), session), sessionId)
        (application as App).syncNow()
    }

    override fun onStart() {
        super.onStart()
        chromeBars?.start()
    }

    override fun onStop() {
        chromeBars?.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Pick up highlights edited on the web while the app was in the background.
        if (!firstResume && bookId != 0L) lifecycleScope.launch { highlights?.sync() }
        // The pace may have just been updated, so redo the estimate rather than wait for the next page turn.
        navigator?.currentLocator?.value?.let { chromeBars?.setMinutesLeft(minutesLeftText(it)) }
        firstResume = false
        lastActivity = android.os.SystemClock.elapsedRealtime()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideStatusBar()
        fileId = intent.getLongExtra(EXTRA_FILE_ID, 0)
        bookId = intent.getLongExtra(EXTRA_BOOK_ID, 0)
        val container = FragmentContainerView(this).apply { id = View.generateViewId() }
        val spinner = ProgressBar(this)
        val chrome = ReaderChrome(this).also { chromeBars = it }
        chrome.applyTheme(readerSettings.effectiveTheme)
        val swipeLayout = SwipeTurnLayout(this, canSwipe = { highlights?.selecting != true }) { forward ->
            navigator?.let { if (forward) it.goForward(animated = false) else it.goBackward(animated = false) }
        }.apply { addView(container, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chrome.top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(swipeLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(chrome.bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (38 * resources.displayMetrics.density).toInt()))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(chrome.backgroundColor)
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        // A bar with Back and Aa, shown by tapping the middle of the page.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF222222.toInt())
            visibility = View.GONE
            val dp = resources.displayMetrics.density
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            fun action(label: String, onClick: () -> Unit) = TextView(this@ReaderActivity).apply {
                text = label; textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener { onClick() }
            }
            addView(action("←") { finish() })
            addView(View(this@ReaderActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(action("Aa") { showReaderSettings(this@ReaderActivity, readerSettings) { applySettings() } })
        }
        toolbar = bar
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // The same purple dot as in the app's other screens, while the server is being talked to.
        val syncDot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Display.dotColor) }
            visibility = View.GONE
        }
        val dotSize = (8 * resources.displayMetrics.density).toInt()
        root.addView(syncDot, FrameLayout.LayoutParams(dotSize, dotSize, Gravity.TOP or Gravity.END).apply {
            // Just left of the battery icon, level with the top bar's text.
            topMargin = (13 * resources.displayMetrics.density).toInt(); marginEnd = (96 * resources.displayMetrics.density).toInt()
        })
        lifecycleScope.launch {
            (application as App).api.syncing.collect { syncDot.visibility = if (it) View.VISIBLE else View.GONE }
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        lifecycleScope.launch {
            try {
                val file = api.downloadFile(fileId, "epub")
                val http = DefaultHttpClient()
                val retriever = AssetRetriever(contentResolver, http)
                val asset = retriever.retrieve(file.toUrl()).getOrElse { error("Cannot read book: $it") }
                val opener = PublicationOpener(DefaultPublicationParser(this@ReaderActivity, http, retriever, null))
                val pub = opener.open(asset, allowUserInteraction = false).getOrElse { error("Cannot open book: $it") }
                publication = pub

                val saved = prefs.getString("loc_$fileId", null)?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
                val serverPct = api.getProgress(fileId)
                val localPct = (saved?.locations?.totalProgression ?: 0.0) * 100
                val serverLocator = suspend { serverPct.takeIf { it > 0 }?.let { pub.locatorFromProgression(it / 100.0) } }
                val initial = when {
                    saved == null -> serverLocator()
                    // Server is further ahead (e.g. read on another device): let the user choose.
                    serverPct > localPct + 1.0 && askJump(serverPct, localPct) -> serverLocator() ?: saved
                    else -> saved
                }

                val hl = HighlightSync(this@ReaderActivity, pub, api, bookId, fileId).also { highlights = it }
                supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
                    initialLocator = initial,
                    initialPreferences = readerSettings.toPreferences(),
                    configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = hl.selectionCallback),
                )
                supportFragmentManager.commit {
                    replace(container.id, EpubNavigatorFragment::class.java, Bundle(), "reader")
                }
                supportFragmentManager.executePendingTransactions()
                val nav = supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
                navigator = nav
                nav.addInputListener(edgeTapListener(nav))
                launch {
                    chromeBars?.setChapters(chaptersOf(pub))
                    chromeBars?.update(nav.currentLocator.value.locations.totalProgression, nav.currentLocator.value.title)
                    buildReadingModel(pub)
                    chromeBars?.setMinutesLeft(minutesLeftText(nav.currentLocator.value))
                }
                hl.nav = nav
                nav.addDecorationListener(HighlightSync.GROUP, hl.decorationListener)
                if (bookId != 0L) {
                    launch { hl.sync() }
                    launch { hl.watchChapters() }
                }
                spinner.visibility = View.GONE
                trackProgress(nav)
            } catch (e: Exception) {
                val offline = e is java.io.IOException && !api.isOnDevice(fileId)
                Toast.makeText(this@ReaderActivity,
                    if (offline) "This book isn't on the device yet. Open it once while connected." else e.message ?: "Failed to open book",
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private suspend fun askJump(serverPct: Double, localPct: Double): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val dialog = Display.dialogBuilder(this)
                .setTitle("Continue from another device?")
                .setMessage("Your server is at ${serverPct.toInt()}%, this device is at ${localPct.toInt()}%.")
                .setCancelable(false)
                .setPositiveButton("Jump to ${serverPct.toInt()}%") { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(true)) }
                .setNegativeButton("Stay here") { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(false)) }
                .show().let { Display.frame(it) }
            cont.invokeOnCancellation { dialog.dismiss() }
        }

    private suspend fun Publication.locatorFromProgression(p: Double): Locator? =
        readingOrder.firstOrNull()?.let { link ->
            // Total progression within the whole publication; Readium resolves it to a resource.
            positions().let { pos ->
                if (pos.isEmpty()) null else pos[((pos.size - 1) * p).toInt().coerceIn(0, pos.size - 1)]
            } ?: Locator(href = link.url(), mediaType = link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML)
        }

    /** Tapping the left/right fifth of the screen turns the page. */
    private fun edgeTapListener(nav: EpubNavigatorFragment) = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            val width = nav.requireView().width
            return when {
                event.point.x < width * 0.2f -> nav.goBackward(animated = false)
                event.point.x > width * 0.8f -> nav.goForward(animated = false)
                else -> {
                    // Middle of the page: show or hide the toolbar.
                    runOnUiThread { toolbar?.let { it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE } }
                    true
                }
            }
        }
    }

    /** Volume up goes back a page, volume down goes forward; the system volume is left alone while reading. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val nav = navigator
        if (nav != null && readerSettings.volumeKeys && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.repeatCount == 0) {
                if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) nav.goForward(animated = false) else nav.goBackward(animated = false)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (navigator != null && readerSettings.volumeKeys && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** Counts the words in every resource, once, so the time left in a chapter can be estimated. */
    private suspend fun buildReadingModel(pub: Publication) {
        fun key(url: Any) = url.toString().substringBefore('#')
        val order = pub.readingOrder
        val words = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            IntArray(order.size) { i ->
                val resource = pub.get(order[i]) ?: return@IntArray 0
                try {
                    val text = resource.read().getOrElse { null }?.toString(Charsets.UTF_8) ?: return@IntArray 0
                    val plain = text.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
                    Regex("\\S+").findAll(plain).count()
                } finally {
                    resource.close()
                }
            }
        }
        val indexByHref = order.withIndex().associate { (i, link) -> key(link.url()) to i }
        val starts = sortedSetOf<Int>()
        fun walk(links: List<org.readium.r2.shared.publication.Link>) {
            for (link in links) {
                indexByHref[key(link.url())]?.let { starts.add(it) }
                walk(link.children)
            }
        }
        walk(pub.tableOfContents)
        chapterStartIndexes = starts.toList()
        wordsByResource = words
    }

    /** How many words into the book [loc] is, or null while word counts are still being made. */
    private fun wordPosition(loc: Locator): Double? {
        val words = wordsByResource ?: return null
        val pub = publication ?: return null
        val href = loc.href.toString().substringBefore('#')
        val index = pub.readingOrder.indexOfFirst { it.url().toString().substringBefore('#') == href }
        if (index < 0) return null
        var before = 0.0
        for (i in 0 until index) before += words[i]
        return before + words[index] * (loc.locations.progression ?: 0.0).coerceIn(0.0, 1.0)
    }

    /**
     * Records one reading step. Only ordinary page turns count: forward, a plausible number of words,
     * and a plausible time (not a flick through pages, not a pause), so jumps and skimming don't skew it.
     */
    private fun samplePace(loc: Locator) {
        val position = wordPosition(loc) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastWordPosition
        val since = lastSampleAt
        if (previous != null && since != null) {
            val words = position - previous
            val seconds = (now - since) / 1000.0
            if (words in 20.0..900.0 && seconds in 3.0..120.0 && words / seconds * 60 <= 1000) {
                paceWords += words
                paceSeconds += seconds
            }
        }
        lastWordPosition = position
        lastSampleAt = now
    }

    /** Pace used for the estimate: what was learned before, nudged by this session once it has enough to say. */
    private fun currentPace(): Double {
        val stored = readerSettings.learnedPace.takeIf { it > 0 } ?: DEFAULT_WORDS_PER_MINUTE
        if (paceSeconds < 90) return stored
        val session = (paceWords / paceSeconds * 60).coerceIn(MIN_PACE, MAX_PACE)
        return (stored + session) / 2
    }

    /** Folds this session's pace into the saved one (30% new, 70% old) so one odd session can't swing it. */
    private fun commitPace() {
        if (paceSeconds >= 60) {
            val session = (paceWords / paceSeconds * 60).coerceIn(MIN_PACE, MAX_PACE)
            val old = readerSettings.learnedPace
            readerSettings.learnedPace = if (old > 0) old * 0.7 + session * 0.3 else session
            paceWords = 0.0
            paceSeconds = 0.0
        }
        lastWordPosition = null
        lastSampleAt = null
    }

    /** "12 min left" for the rest of the current chapter at a typical reading speed, or empty until it is known. */
    private fun minutesLeftText(loc: Locator): String {
        val words = wordsByResource ?: return ""
        val pub = publication ?: return ""
        val href = loc.href.toString().substringBefore('#')
        val index = pub.readingOrder.indexOfFirst { it.url().toString().substringBefore('#') == href }
        if (index < 0) return ""
        // The chapter ends where the next one starts; with no table of contents it runs to the end of the book.
        val end = chapterStartIndexes.firstOrNull { it > index } ?: words.size
        val within = (loc.locations.progression ?: 0.0).coerceIn(0.0, 1.0)
        var remaining = words[index] * (1 - within)
        for (i in index + 1 until end) remaining += words[i]
        val minutes = Math.round(remaining / currentPace()).toInt()
        return if (remaining < 10) "<1 min left" else "${maxOf(minutes, 1)} min left"
    }

    /** Where each table-of-contents chapter starts, as a fraction of the book. */
    private suspend fun chaptersOf(pub: Publication): List<Chapter> {
        val positions = pub.positions()
        fun key(url: Any) = url.toString().substringBefore('#')
        val starts = HashMap<String, Double>()
        for (p in positions) starts.putIfAbsent(key(p.href), p.locations.totalProgression ?: continue)
        val out = mutableListOf<Chapter>()
        val seen = HashSet<String>()
        fun walk(links: List<org.readium.r2.shared.publication.Link>) {
            for (link in links) {
                val href = key(link.url())
                val title = link.title
                val start = starts[href]
                if (!title.isNullOrBlank() && start != null && seen.add(href)) out.add(Chapter(title.trim(), start))
                walk(link.children)
            }
        }
        walk(pub.tableOfContents)
        return out
    }

    private fun applySettings() {
        navigator?.submitPreferences(readerSettings.toPreferences())
        chromeBars?.let { c ->
            c.applyTheme(readerSettings.effectiveTheme)
            (c.top.parent as? View)?.parent?.let { (it as? View)?.setBackgroundColor(c.backgroundColor) }
        }
    }

    @OptIn(FlowPreview::class)
    private fun trackProgress(nav: EpubNavigatorFragment) {
        lifecycleScope.launch {
            nav.currentLocator.collect { loc ->
                samplePace(loc)
                chromeBars?.update(loc.locations.totalProgression, loc.title)
                chromeBars?.setMinutesLeft(minutesLeftText(loc))
                touchActivity()
                loc.locations.totalProgression?.let { p ->
                    if (openedAt == null) openedAt = p else if (p != openedAt) moved = true
                    if (moved && lastSeenProgress != null && p != lastSeenProgress && ++pagesSinceSync >= PAGES_PER_SYNC) {
                        pagesSinceSync = 0
                        lastPercent = p * 100
                        (application as App).outbox.progress(fileId, lastPercent)
                        (application as App).syncNow()
                    }
                    lastSeenProgress = p
                }
                loc.locations.totalProgression?.let { p ->
                    currentProgress = p * 100
                    if (startProgress == null) startProgress = p * 100
                }
            }
        }
        lifecycleScope.launch {
            nav.currentLocator.debounce(1500).collect { loc ->
                if (!moved) return@collect
                prefs.edit().putString("loc_$fileId", loc.toJSON().toString()).apply()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        touchActivity()
        lastActivity = null
        commitPace()
        // GlobalScope-free: the app scope outlives this activity, so the upload survives finish().
        (application as App).appScope.launch { uploadSession() }
        navigator?.currentLocator?.value?.let { loc ->
            prefs.edit().putString("loc_$fileId", loc.toJSON().toString()).apply()
            loc.locations.totalProgression?.let { p ->
                val pct = p * 100
                if (moved && pct != lastPercent) {
                    lastPercent = pct
                    (application as App).outbox.progress(fileId, pct)
                    (application as App).syncNow()
                }
            }
        }
    }

    override fun onDestroy() {
        publication?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_ID = "fileId"
        const val EXTRA_BOOK_ID = "bookId"
        private const val PAGES_PER_SYNC = 10
        private const val IDLE_LIMIT_MS = 120_000L
        private const val MIN_PACE = 80.0
        private const val MAX_PACE = 600.0
    }
}
