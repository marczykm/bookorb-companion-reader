package app.companion.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Finished reads, newest first, grouped by month, with a summary of the year. */
@Composable
fun HistoryScreen(app: App, onBack: () -> Unit, onLogout: () -> Unit, onOpenBook: (BookCard) -> Unit) {
    val scope = rememberCoroutineScope()
    var year by remember { mutableStateOf<Int?>(LocalDate.now().year) }   // null = all time
    var query by remember { mutableStateOf("") }
    var covers by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<HistorySummary?>(null) }
    val items = remember { mutableStateListOf<HistoryItem>() }
    var months by remember { mutableStateOf<List<HistoryMonth>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()

    fun load(reset: Boolean) {
        if (loading && !reset) return
        loading = true; error = null
        scope.launch {
            try {
                if (reset) summary = app.api.historySummary(year)
                val page = app.api.history(year, query, if (reset) 0 else items.size)
                if (reset) { items.clear(); months = page.months.orEmpty() }
                items.addAll(page.items); total = page.total
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
    LaunchedEffect(year, query) {
        if (query.isNotEmpty()) delay(400)
        load(true)
    }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= grid.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && items.size < total && !loading) load(false) }

    // Rows are split into the server's months by count, in order, as the web client does.
    val sections = remember(items.size, months) {
        var index = 0
        months.mapNotNull { m ->
            val rows = items.subList(minOf(index, items.size), minOf(index + m.reads, items.size)).toList()
            index += m.reads
            if (rows.isEmpty()) null else m to rows
        }
    }

    SubScreen("History", onBack, actions = { OfflineIcon(app) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyVerticalGrid(
                state = grid, columns = GridCells.Adaptive(90.dp), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search books") }, modifier = Modifier.fillMaxWidth())
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!covers, { covers = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("List") }
                        SegmentedButton(covers, { covers = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Covers") }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(summary?.years.orEmpty(), key = { it.year }) { y ->
                            FilterChip(year == y.year, { year = y.year }, { Text("${y.year} ${y.reads}") })
                        }
                        item { FilterChip(year == null, { year = null }, { Text("All time") }) }
                    }
                }
                summary?.let { s -> item(span = { GridItemSpan(maxLineSpan) }) { NumbersCard(s, year) } }
                if (covers) {
                    items(items.size) { i ->
                        val item = items[i]
                        Column(Modifier.clickable(enabled = item.bookId != null) { item.bookId?.let { onOpenBook(BookCard(id = it, title = item.title, authors = item.authors)) } }) {
                            Cover(app, item.bookId, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                            Text(item.title ?: "Untitled", style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    sections.forEach { (month, rows) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "m:${month.month}") { MonthHeader(month) }
                        items(rows.size, span = { GridItemSpan(maxLineSpan) }, key = { "r:${rows[it].id}" }) { i ->
                            val item = rows[i]
                            HistoryRow(app, item) { item.bookId?.let { onOpenBook(BookCard(id = it, title = item.title, authors = item.authors)) } }
                        }
                    }
                }
            }
            if (loading && items.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (loaded && items.isEmpty() && error == null) Text("No reads yet", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OfflineEmpty(app, hasContent = items.isNotEmpty() || summary != null, failed = error != null)
        }
    }
}

@Composable
private fun MonthHeader(month: HistoryMonth) {
    val title = if (month.month == "undated") "Undated" else runCatching {
        val m = java.time.YearMonth.parse(month.month)
        "${monthName(m.monthValue)} ${m.year}".replaceFirstChar(Char::uppercase)
    }.getOrDefault(month.month)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("${month.reads} read${if (month.reads == 1) "" else "s"}" + if (month.seconds > 0) " · ${shortDuration(month.seconds)}" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryRow(app: App, item: HistoryItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = item.bookId != null, onClick = onClick), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cover(app, item.bookId, Modifier.width(52.dp).aspectRatio(2f / 3f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title ?: "Untitled", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                if (item.authors.isNotEmpty()) add(item.authors.joinToString())
                item.seriesName?.let { add(it + (item.seriesIndex.text()?.let { i -> " #$i" } ?: "")) }
            }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                item.format?.let { Badge(it.uppercase(), MaterialTheme.colorScheme.primary) }
                if (item.outcome != "completed") Badge(if (item.outcome == "abandoned") "Abandoned" else "Skimmed", MaterialTheme.colorScheme.tertiary)
                item.readNumber?.takeIf { it > 1 }?.let { Badge("Read $it", MaterialTheme.colorScheme.secondary) }
                if (item.highlightCount > 0) Text("✎ ${item.highlightCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.rating?.takeIf { it > 0 }?.let {
                    Icon(Icons.Default.Star, null, Modifier.size(12.dp), tint = Color(0xFFF5C623))
                    Text("%g".format(it), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        parseDay(item.endedOn)?.let { d ->
            Text(d.format(DateTimeFormatter.ofPattern("d MMM\nEEE")), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
        }
    }
}

/** "2026 in numbers": totals, a bar per month and the reading goal. */
@Composable
private fun NumbersCard(summary: HistorySummary, year: Int?) {
    InfoCard("${year ?: "All time"} in numbers") {
        Row(Modifier.fillMaxWidth()) {
            StatBlock("${summary.reads}", "reads", Modifier.weight(1f))
            StatBlock("${summary.books}", "books", Modifier.weight(1f))
            StatBlock("${summary.rereads}", "rereads", Modifier.weight(1f))
        }
        if (year != null && summary.months.isNotEmpty()) {
            val now = LocalDate.now()
            BarChart(
                summary.months.map { it.reads.toDouble() }, summary.months.map { monthShort(it.month) },
                highlight = if (year == now.year) summary.months.indexOfFirst { it.month == now.monthValue }.takeIf { it >= 0 } else null,
            )
        }
        summary.goal?.let { g ->
            val met = g.reached >= g.target
            val text = when {
                met -> parseDay(g.metOn)?.let { "Goal of ${g.target} met on ${it.format(DateTimeFormatter.ofPattern("d MMM"))}" } ?: "Goal of ${g.target} met"
                else -> "${g.reached} of ${g.target} toward your goal"
            }
            Text((if (met) "✓ " else "◎ ") + text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
