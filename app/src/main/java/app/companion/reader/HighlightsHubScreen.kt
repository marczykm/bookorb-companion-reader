package app.companion.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.YearMonth

/** All the user's highlights across the library, newest first and grouped by month, with search and filters. */
@Composable
fun HighlightsHubScreen(app: App, onBack: () -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<HubOverview?>(null) }
    val items = remember { mutableStateListOf<HubHighlight>() }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var colors by remember { mutableStateOf(setOf<String>()) }
    var onlyNotes by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    fun load(reset: Boolean) {
        if (loading && !reset) return
        loading = true; error = null
        val target = if (reset) 1 else page + 1
        scope.launch {
            try {
                if (reset && overview == null) overview = app.api.hubOverview()
                val r = app.api.hubHighlights(target, query, colors.sorted(), onlyNotes)
                if (reset) items.clear()
                items.addAll(r.items); total = r.total; page = target
            } catch (e: AuthException) {
                app.session.clearTokens(); onLogout()
            } catch (e: Exception) {
                error = e.message ?: "Failed to load"
            } finally {
                loading = false; loaded = true
            }
        }
    }
    BackgroundRefresh(app, failing = error != null, refresh = { load(true) })
    LaunchedEffect(query, colors, onlyNotes) {
        if (query.isNotEmpty()) delay(400)
        load(true)
    }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= list.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.size < total && !loading) load(false) }

    val sections = remember(items.size) {
        val order = mutableListOf<String>()
        val groups = HashMap<String, MutableList<HubHighlight>>()
        for (h in items) {
            val key = parseMonth(h.highlightedAt ?: h.createdAt)?.toString() ?: "undated"
            if (key !in groups) order.add(key)
            groups.getOrPut(key) { mutableListOf() }.add(h)
        }
        order.map { it to groups.getValue(it).toList() }
    }

    SubScreen("Highlights", onBack, actions = { OfflineIcon(app) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search highlights") }, modifier = Modifier.fillMaxWidth())
                }
                overview?.let { o ->
                    item {
                        InfoCard(null) {
                            Row(Modifier.fillMaxWidth()) {
                                StatBlock("${o.total}", "highlights", Modifier.weight(1f))
                                StatBlock("${o.books}", "books", Modifier.weight(1f))
                                StatBlock("${o.withNotes}", "with notes", Modifier.weight(1f))
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { FilterChip(onlyNotes, { onlyNotes = !onlyNotes }, { Text("With notes") }) }
                                items(o.colorBreakdown, key = { it.color }) { c ->
                                    FilterChip(c.color in colors, { colors = if (c.color in colors) colors - c.color else colors + c.color }, {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(HighlightSync.tintOf(c.color))))
                                            Text("${c.count}")
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
                sections.forEach { (key, rows) ->
                    item(key = "m:$key") {
                        Text(
                            if (key == "undated") "Undated" else YearMonth.parse(key).let { "${monthName(it.monthValue)} ${it.year}".replaceFirstChar(Char::uppercase) },
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(rows, key = { "h:${it.id}" }) { HighlightRow(it) }
                }
            }
            if (loading && items.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (loaded && items.isEmpty() && error == null) Text("No highlights", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OfflineEmpty(app, hasContent = items.isNotEmpty(), failed = error != null)
        }
    }
}

@Composable
private fun HighlightRow(item: HubHighlight) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color(HighlightSync.tintOf(item.color))))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
            item.note?.takeIf { it.isNotEmpty() }?.let {
                Text("✎ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
            val footer = buildList {
                add(item.bookTitle ?: "Unknown book")
                item.author?.takeIf { it.isNotEmpty() }?.let { add(it) }
                item.chapterTitle?.takeIf { it.isNotEmpty() }?.let { add(it) }
                parseDay(item.highlightedAt ?: item.createdAt)?.let { add("${it.dayOfMonth} ${monthName(it.monthValue).take(3)}") }
            }.joinToString(" · ")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.starredAt != null) Text("★", color = Color(0xFFF5C623), style = MaterialTheme.typography.labelMedium)
                Text(footer, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.origin != "web") Badge(item.origin.replaceFirstChar(Char::uppercase), MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded) TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text)) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Copy") }
            }
        }
    }
}

/** The month a server timestamp falls in, whether it has a time and zone or only a date. */
private fun parseMonth(iso: String?): YearMonth? =
    iso?.let { s -> runCatching { YearMonth.from(OffsetDateTime.parse(s)) }.getOrNull() ?: parseDay(s)?.let { YearMonth.from(it) } }
