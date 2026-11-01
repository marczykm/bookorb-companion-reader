package app.companion.reader

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What the Home tab can show, in the default order. */
val HOME_ITEMS: List<Pair<String, String>> = listOf(
    "hero" to "Continue reading card",
    "pet" to "Reading companion",
    "reading-streak" to "Reading streak",
    "reading-goal" to "Yearly goal",
    "reading-rhythm" to "Reading rhythm",
    "year-projection" to "On pace for the year",
    "monthly-challenge" to "Monthly challenge",
    "highlight-of-the-day" to "Highlight of the day",
    "long-wait" to "Waiting the longest",
    "library-overview" to "Library overview",
    "shelf:continue" to "More in progress",
    "shelf:next" to "Up next in series",
    "shelf:want" to "Want to read",
    "shelf:recent" to "Recently added",
)

val SIZES = listOf("s", "m", "l")

/** Everything starts small except the continue-reading card and its shelf. */
private val MEDIUM_BY_DEFAULT = setOf("hero", "shelf:continue")

/** Cards that can sit two to a row when small. Hero and shelves scale their covers instead. */
val HALF_WIDTH_ITEMS: Set<String> = HOME_ITEMS.map { it.first }.filter { it != "hero" && !it.startsWith("shelf:") }.toSet()

/** The order and visibility of Home items, kept on the device. */
class HomeLayout(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val defaults = HOME_ITEMS.map { it.first }

    var order by mutableStateOf(load())
        private set
    var hidden by mutableStateOf(prefs.getString("home_hidden", "")!!.split(',').filter { it.isNotEmpty() }.toSet())
        private set

    /** "s", "m" or "l" per item; missing means the default for that item. */
    var sizes by mutableStateOf(
        prefs.getString("home_sizes", "")!!.split(',').mapNotNull { e -> e.split('=').takeIf { it.size == 2 && it[1] in SIZES }?.let { it[0] to it[1] } }.toMap(),
    )
        private set

    fun sizeOf(id: String) = sizes[id] ?: if (id in MEDIUM_BY_DEFAULT) "m" else "s"

    fun cycleSize(id: String) {
        sizes = sizes + (id to SIZES[(SIZES.indexOf(sizeOf(id)) + 1) % SIZES.size])
        save()
    }

    /** Visible items grouped into rows: neighbouring small cards share one. */
    val rows: List<List<String>> get() {
        val out = mutableListOf<MutableList<String>>()
        for (id in visible) {
            val last = out.lastOrNull()
            if (id in HALF_WIDTH_ITEMS && sizeOf(id) == "s" && last != null && last.size == 1 &&
                last[0] in HALF_WIDTH_ITEMS && sizeOf(last[0]) == "s") last.add(id)
            else out.add(mutableListOf(id))
        }
        return out
    }

    private fun load(): List<String> {
        val saved = prefs.getString("home_order", "")!!.split(',').filter { it in defaults }
        // Items added in later versions go at the end of the saved order.
        return saved + defaults.filter { it !in saved }
    }

    val visible get() = order.filter { it !in hidden }

    fun move(id: String, delta: Int) {
        val from = order.indexOf(id)
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from < 0 || from == to) return
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        save()
    }

    fun setVisible(id: String, visible: Boolean) {
        hidden = if (visible) hidden - id else hidden + id
        save()
    }

    fun reset() {
        order = defaults; hidden = emptySet(); sizes = emptyMap(); save()
    }

    private fun save() {
        prefs.edit().putString("home_sizes", sizes.entries.joinToString(",") { "${it.key}=${it.value}" })
            .putString("home_order", order.joinToString(",")).putString("home_hidden", hidden.joinToString(",")).apply()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeHomeSheet(layout: HomeLayout, onDismiss: () -> Unit) {
    val titles = remember { HOME_ITEMS.toMap() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Customize Home", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { layout.reset() }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(layout.order, key = { _, id -> id }) { index, id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = id !in layout.hidden, onCheckedChange = { layout.setVisible(id, it) })
                        Text(titles[id] ?: id, Modifier.weight(1f).padding(horizontal = 12.dp))
                        TextButton(onClick = { layout.cycleSize(id) }) { Text(layout.sizeOf(id).uppercase()) }
                        IconButton(onClick = { layout.move(id, -1) }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { layout.move(id, 1) }, enabled = index < layout.order.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, "Move down")
                        }
                    }
                }
            }
        }
    }
}
