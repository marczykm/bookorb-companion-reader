package app.bookorbit.reader

import android.graphics.Color
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Two-way highlight sync with BookOrbit. Positions are stored on the server as EPUB CFIs, the same
 * format the web reader writes, so highlights made here show up there and vice versa.
 *
 * Device to server: the selection's CFI is computed in the page with foliate-js's CFI code (the
 * implementation the web reader uses). Server to device: the CFI's spine step picks the chapter and
 * the highlighted text is located inside it, so a quote that repeats within a chapter may land on
 * its first occurrence.
 */
class HighlightSync(
    private val activity: AppCompatActivity,
    private val pub: Publication,
    private val api: BookOrbitApi,
    private val bookId: Long,
    private val fileId: Long,
) {
    var nav: EpubNavigatorFragment? = null
    private val items = linkedMapOf<Long, Annotation>()

    /** Text just before and after each highlight, so a quote that repeats in a chapter lands on the right one. */
    private val context = mutableMapOf<Long, Anchor>()

    private class Anchor(val before: String, val after: String, val locations: Locator.Locations)
    private val cfiScript by lazy { activity.assets.open("epubcfi.js").bufferedReader().readText() }

    val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_HIGHLIGHT, Menu.FIRST, "Highlight")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != MENU_HIGHLIGHT) return false
            // The selection disappears when the mode finishes, so read it first.
            activity.lifecycleScope.launch {
                try {
                    highlightSelection()
                } finally {
                    mode.finish()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    val decorationListener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            val annotation = event.decoration.id.toLongOrNull()?.let { items[it] } ?: return false
            // Readium reports taps from its JavaScript bridge thread.
            activity.runOnUiThread { showOptions(annotation) }
            return true
        }
    }

    /** Replaces the local set with the server's and redraws. Safe to call repeatedly. */
    suspend fun sync() {
        val remote = api.annotations(bookId, fileId) ?: return
        items.clear()
        remote.forEach { items[it.id] = it }
        applyPending()
        context.keys.retainAll(items.keys)
        loadContext()
        render()
    }

    /** Puts back changes made here that the server hasn't received yet, so they show while offline. */
    private fun applyPending() {
        val outbox = (activity.application as App).outbox
        for (op in outbox.pendingOps()) {
            val d = op.data
            if (d["bookId"]?.jsonPrimitive?.content?.toLongOrNull() != bookId) continue
            when (op.type) {
                "ann_create" -> items[-op.id] = Annotation(
                    id = -op.id, cfi = d["cfi"]!!.jsonPrimitive.content, text = d["text"]!!.jsonPrimitive.content,
                    color = d["color"]!!.jsonPrimitive.content,
                )
                "ann_color" -> d["annId"]!!.jsonPrimitive.content.toLong().let { id ->
                    items[id]?.let { items[id] = it.copy(color = d["color"]!!.jsonPrimitive.content) }
                }
                "ann_delete" -> items.remove(d["annId"]!!.jsonPrimitive.content.toLong())
            }
        }
    }

    /** Re-anchors highlights each time the reader moves into another chapter. */
    suspend fun watchChapters() {
        val nav = nav ?: return
        nav.currentLocator.map { it.href.toString().substringBefore('#') }.distinctUntilChanged().collect {
            kotlinx.coroutines.delay(300) // let the new chapter finish loading
            if (loadContext()) render()
        }
    }

    /** Resolves the CFIs of highlights in the chapter on screen to their surrounding text. True if anything new was found. */
    private suspend fun loadContext(): Boolean {
        val nav = nav ?: return false
        val chapter = spineIndexOf(nav.currentLocator.value.href.toString())
        val pending = items.values.filter { it.id !in context && it.cfi != null && spineIndexOfCfi(it.cfi) == chapter }
        if (pending.isEmpty()) return false
        val arg = JsonArray(pending.map { buildJsonObject { put("id", it.id.toString()); put("cfi", it.cfi!!) } }).toString()
        val raw = nav.evaluateJavascript("$cfiScript;($CONTEXT_OF_CFIS)($arg)") ?: return false
        val found = runCatching {
            Json.parseToJsonElement(Json.parseToJsonElement(raw).jsonPrimitive.content).jsonObject
        }.getOrNull() ?: return false
        var changed = false
        for ((id, value) in found) {
            val o = value.jsonObject
            val selector = o["s"]?.jsonPrimitive?.content
            context[id.toLong()] = Anchor(
                before = o["b"]?.jsonPrimitive?.content ?: "",
                after = o["a"]?.jsonPrimitive?.content ?: "",
                locations = if (selector != null) Locator.Locations(otherLocations = mapOf("cssSelector" to selector)) else Locator.Locations(),
            )
            changed = true
        }
        return changed
    }

    private suspend fun highlightSelection() {
        val nav = nav ?: return
        val selection = nav.currentSelection() ?: return
        val text = selection.locator.text.highlight?.trim().orEmpty()
        val spine = spineIndexOf(selection.locator.href.toString())
        if (text.isEmpty() || spine < 0) return toast("Can't highlight this selection")

        val raw = nav.evaluateJavascript("$cfiScript;($SELECTION_TO_CFI)")
        val local = raw?.let { runCatching { Json.parseToJsonElement(it).jsonPrimitive.contentOrNull }.getOrNull() }
            ?: return toast("Can't highlight this selection")
        val inner = local.removePrefix("epubcfi(").removeSuffix(")")
        val cfi = "epubcfi(/6/${(spine + 1) * 2}!$inner)"

        val app = activity.application as App
        val tempId = app.outbox.createAnnotation(bookId, fileId, cfi, text, COLORS.first().second)
        app.syncNow()
        items[tempId] = Annotation(id = tempId, cfi = cfi, text = text, color = COLORS.first().second)
        context[tempId] = Anchor(
            before = selection.locator.text.before.orEmpty().takeLast(40),
            after = selection.locator.text.after.orEmpty().take(40),
            locations = selection.locator.locations,
        )
        nav.clearSelection()
        render()
    }

    private fun showOptions(a: Annotation) {
        val names = COLORS.map { it.first.replaceFirstChar(Char::uppercase) } + "Delete highlight"
        AlertDialog.Builder(activity)
            .setTitle(a.text.take(80))
            .setItems(names.toTypedArray()) { _, which ->
                activity.lifecycleScope.launch {
                    val app = activity.application as App
                    if (which == COLORS.size) {
                        app.outbox.deleteAnnotation(bookId, a.id)
                        items.remove(a.id)
                    } else {
                        val color = COLORS[which].second
                        app.outbox.recolorAnnotation(bookId, a.id, color)
                        items[a.id] = a.copy(color = color)
                    }
                    app.syncNow()
                    render()
                }
            }
            .show()
    }

    private suspend fun render() {
        val decorations = items.values.mapNotNull { a ->
            val link = a.cfi?.let(::spineIndexOfCfi)?.let { pub.readingOrder.getOrNull(it) } ?: return@mapNotNull null
            if (a.text.isBlank()) return@mapNotNull null
            val anchor = context[a.id]
            val locator = Locator(
                href = link.url(),
                mediaType = link.mediaType ?: MediaType.XHTML,
                locations = anchor?.locations ?: Locator.Locations(),
                text = if (anchor != null) Locator.Text(before = anchor.before, highlight = a.text, after = anchor.after) else Locator.Text(highlight = a.text),
            )
            val tint = tintOf(a.color)
            val style = if (a.style == "underline") Decoration.Style.Underline(tint = tint) else Decoration.Style.Highlight(tint = tint)
            Decoration(id = a.id.toString(), locator = locator, style = style)
        }
        nav?.applyDecorations(decorations, GROUP)
    }

    private fun spineIndexOf(href: String): Int {
        val target = href.substringBefore('#')
        return pub.readingOrder.indexOfFirst { it.url().toString().substringBefore('#') == target }
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val GROUP = "bookorbit-highlights"
        private const val MENU_HIGHLIGHT = 0x426f

        /** Names with the hex values the web reader uses. */
        val COLORS = listOf(
            "yellow" to "#FACC15", "green" to "#4ADE80", "blue" to "#38BDF8", "pink" to "#F472B6",
        )
        private val ALL_NAMES = mapOf(
            "yellow" to "#FACC15", "green" to "#4ADE80", "blue" to "#38BDF8", "pink" to "#F472B6", "orange" to "#FB923C",
            "red" to "#F87171", "olive" to "#84CC16", "cyan" to "#22D3EE", "purple" to "#C084FC", "gray" to "#9CA3AF",
        )

        fun tintOf(color: String): Int =
            runCatching { Color.parseColor(if (color.startsWith("#")) color else ALL_NAMES[color.lowercase()] ?: "#FACC15") }
                .getOrDefault(Color.parseColor("#FACC15"))

        /** The top-level step of a CFI, `/6/N`, names spine item N/2 - 1. */
        fun spineIndexOfCfi(cfi: String): Int? =
            Regex("""^epubcfi\(/6/(\d+)""").find(cfi)?.groupValues?.get(1)?.toIntOrNull()?.let { it / 2 - 1 }?.takeIf { it >= 0 }

        private const val CONTEXT_OF_CFIS = """function (items) {
            var out = {};
            items.forEach(function (it) {
                try {
                    var parts = window.__boCfi.parse(it.cfi);
                    (parts.parent || parts).shift();
                    var r = window.__boCfi.toRange(document, parts);
                    var pre = document.createRange();
                    pre.setStart(document.body, 0);
                    pre.setEnd(r.startContainer, r.startOffset);
                    var post = document.createRange();
                    post.setStart(r.endContainer, r.endOffset);
                    post.setEnd(document.body, document.body.childNodes.length);
                    // A selector for the element holding the start of the range: the text is then searched inside it.
                    var el = r.startContainer.nodeType === 1 ? r.startContainer : r.startContainer.parentElement;
                    var path = [];
                    while (el && el !== document.body && el.parentElement) {
                        var i = Array.prototype.indexOf.call(el.parentElement.children, el) + 1;
                        path.unshift(el.tagName.toLowerCase() + ':nth-child(' + i + ')');
                        el = el.parentElement;
                    }
                    out[it.id] = { b: pre.toString().slice(-40), a: post.toString().slice(0, 40), s: 'body > ' + path.join(' > ') };
                } catch (e) {}
            });
            return JSON.stringify(out);
        }"""

        // Skips the elements Readium adds to the page so they don't shift sibling indexes.
        private const val SELECTION_TO_CFI = """function () {
            var s = window.getSelection();
            if (!s || s.rangeCount === 0 || s.isCollapsed) return null;
            var filter = function (n) {
                return n.nodeType === 1 && /^(readium|r2)/i.test(n.id || '') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
            };
            return window.__boCfi.fromRange(s.getRangeAt(0), filter);
        }()"""
    }
}
