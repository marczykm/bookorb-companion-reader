package app.bookorbit.reader

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
    private var navigator: EpubNavigatorFragment? = null
    private val readerSettings by lazy { ReaderSettings(this) }
    private var toolbar: View? = null
    private var chromeBars: ReaderChrome? = null
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
        chrome.applyTheme(readerSettings.theme)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chrome.top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(chrome.bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (28 * resources.displayMetrics.density).toInt()))
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
                launch { chromeBars?.setChapters(chaptersOf(pub)); chromeBars?.update(nav.currentLocator.value.locations.totalProgression, nav.currentLocator.value.title) }
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
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Continue from another device?")
                .setMessage("BookOrbit is at ${serverPct.toInt()}%, this device is at ${localPct.toInt()}%.")
                .setCancelable(false)
                .setPositiveButton("Jump to ${serverPct.toInt()}%") { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(true)) }
                .setNegativeButton("Stay here") { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(false)) }
                .show()
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
                event.point.x < width * 0.2f -> nav.goBackward(animated = true)
                event.point.x > width * 0.8f -> nav.goForward(animated = true)
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
                if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) nav.goForward(animated = true) else nav.goBackward(animated = true)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (navigator != null && readerSettings.volumeKeys && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
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
            c.applyTheme(readerSettings.theme)
            (c.top.parent as? View)?.parent?.let { (it as? View)?.setBackgroundColor(c.backgroundColor) }
        }
    }

    @OptIn(FlowPreview::class)
    private fun trackProgress(nav: EpubNavigatorFragment) {
        lifecycleScope.launch {
            nav.currentLocator.collect { loc ->
                chromeBars?.update(loc.locations.totalProgression, loc.title)
                touchActivity()
                loc.locations.totalProgression?.let { p ->
                    if (openedAt == null) openedAt = p else if (p != openedAt) moved = true
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
                loc.locations.totalProgression?.let { p ->
                    lastPercent = p * 100
                    (application as App).outbox.progress(fileId, lastPercent)
                    (application as App).syncNow()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        touchActivity()
        lastActivity = null
        // GlobalScope-free: the app scope outlives this activity, so the upload survives finish().
        (application as App).appScope.launch { uploadSession() }
        navigator?.currentLocator?.value?.let { loc ->
            prefs.edit().putString("loc_$fileId", loc.toJSON().toString()).apply()
            loc.locations.totalProgression?.let { p ->
                val pct = p * 100
                if (moved && pct != lastPercent) {
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
        private const val IDLE_LIMIT_MS = 120_000L
    }
}
