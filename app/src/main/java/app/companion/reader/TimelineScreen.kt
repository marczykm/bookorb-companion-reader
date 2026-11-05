package app.companion.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val sourceLabels = mapOf(
    "bookorbit" to "Web reader", "web" to "Web reader", "manual" to "Added by hand", "ios" to "iOS app", "watchos" to "Apple Watch",
    "android" to "Android app", "koreader" to "KOReader", "kobo" to "Kobo",
)

private fun bucket(source: String?) = if (source == null || source == "web" || source == "manual") "bookorbit" else source

private sealed interface Row {
    data class Single(val s: SessionsPage.Item, val gap: Double?) : Row
    data class Quick(val id: String, val count: Int, val seconds: Double, val items: List<SessionsPage.Item>) : Row
}

private data class DayBlock(val day: LocalDate, val seconds: Double, val count: Int, val rows: List<Row>)

/** Every reading session of one book: a summary, a chart and the sessions grouped by day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineScreen(app: App, book: BookCard, title: String, sessions: SessionsPage, onBack: () -> Unit) {
    var device by remember { mutableStateOf<String?>(null) }   // null = all devices
    var menu by remember { mutableStateOf(false) }
    var openRuns by remember { mutableStateOf(setOf<String>()) }
    val st = sessions.stats

    val items = remember(sessions, device) {
        sessions.items.filter { device == null || bucket(it.source) == device }.sortedByDescending { parseInstant(it.startedAt) }
    }
    val days = remember(items) {
        // Gap since the previous (earlier) session ended, across the whole list.
        val gap = HashMap<Long, Double>()
        var prevEnd: ZonedDateTime? = null
        for (s in items.reversed()) {
            val start = parseInstant(s.startedAt)
            if (prevEnd != null && start != null) gap[s.id] = Duration.between(prevEnd, start).seconds.toDouble().coerceAtLeast(0.0)
            prevEnd = parseInstant(s.endedAt) ?: prevEnd
        }
        val order = mutableListOf<LocalDate>()
        val groups = HashMap<LocalDate, MutableList<SessionsPage.Item>>()
        for (s in items) {
            val d = parseInstant(s.startedAt)?.toLocalDate() ?: continue
            if (d !in groups) order.add(d)
            groups.getOrPut(d) { mutableListOf() }.add(s)
        }
        order.map { d ->
            val list = groups.getValue(d)
            val rows = mutableListOf<Row>()
            var run = mutableListOf<SessionsPage.Item>()
            fun flush() {
                if (run.size >= 2) rows.add(Row.Quick("q${run[0].id}", run.size, run.sumOf { it.durationSeconds }, run.toList()))
                else run.forEach { rows.add(Row.Single(it, gap[it.id])) }
                run = mutableListOf()
            }
            for (s in list) { if (s.durationSeconds < 10) run.add(s) else { flush(); rows.add(Row.Single(s, gap[s.id])) } }
            flush()
            DayBlock(d, list.sumOf { it.durationSeconds }, list.size, rows)
        }
    }
    val longest = sessions.items.maxOfOrNull { it.durationSeconds } ?: -1.0

    SubScreen("Timeline", onBack, actions = { OfflineIcon(app) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(app, book.id, Modifier.width(64.dp).aspectRatio(2f / 3f))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    fullDate(st.lastSessionAt)?.let { Text("Last read $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            // Summary
            val left = secondsLeft(st, st.latestEndProgress)
            InfoCard(null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val since = parseInstant(st.firstSessionAt)?.format(DateTimeFormatter.ofPattern("d MMM"))?.uppercase().orEmpty()
                    Text("THIS READ · SINCE $since", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${st.totalSessions} sessions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val (finishDay, finishNote) = finish(st, left)
                Row(Modifier.fillMaxWidth()) {
                    StatBlock(hm(st.totalSeconds), "this read", Modifier.weight(1f))
                    StatBlock(left?.let { "≈${hm(it)}" } ?: "–", "left at your pace", Modifier.weight(1f))
                    StatBlock(finishDay, finishNote, Modifier.weight(1f))
                }
                ProgressChart(st)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("■ Read ${hm(st.totalSeconds)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("— Progress", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    st.bySource.forEach { sl ->
                        Text("${sourceLabels[sl.bucket] ?: sl.bucket}  ${hm(sl.totalSeconds)}", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Device filter
            Box {
                OutlinedButton(onClick = { menu = true }) { Text((device?.let { sourceLabels[it] ?: it } ?: "All devices") + " ▾") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("All devices") }, onClick = { device = null; menu = false })
                    st.bySource.forEach { sl -> DropdownMenuItem(text = { Text(sourceLabels[sl.bucket] ?: sl.bucket) }, onClick = { device = sl.bucket; menu = false }) }
                }
            }

            // Days
            days.forEach { day ->
                DayHeader(day)
                day.rows.forEach { row ->
                    when (row) {
                        is Row.Single -> SessionRow(row.s, row.gap, longest)
                        is Row.Quick -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.clickable { openRuns = if (row.id in openRuns) openRuns - row.id else openRuns + row.id }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                TimeLabel(row.items.first().startedAt)
                                Text("${row.count} quick looks · ${hm(row.seconds)} ${if (row.id in openRuns) "▴" else "▾"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (row.id in openRuns) row.items.forEach { SessionRow(it, null, longest) }
                        }
                    }
                }
            }
            items.lastOrNull()?.let { first ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeLabel(first.startedAt)
                    Column {
                        Text("⚑ Started reading", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text("On ${sourceLabels[bucket(first.source)] ?: bucket(first.source)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Sessions are recorded on every device you read or listen on, including Kobo and KOReader.", Modifier.fillMaxWidth().padding(top = 12.dp),
                style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The day the book would be finished, if reading goes on at the average daily pace so far. */
private fun finish(st: SessionsPage.Stats, left: Double?): Pair<String, String> {
    val first = parseInstant(st.firstSessionAt)
    if (left == null || left <= 0 || first == null) return "–" to "to finish"
    val days = (Duration.between(first, ZonedDateTime.now()).seconds / 86400.0).coerceAtLeast(1.0)
    val perDay = st.totalSeconds / days
    if (perDay <= 0) return "–" to "to finish"
    val date = ZonedDateTime.now().plusSeconds((left / perDay * 86400).toLong())
    return date.format(DateTimeFormatter.ofPattern("EEE")) to "to finish, ${date.format(DateTimeFormatter.ofPattern("d MMM"))}"
}

@Composable
private fun ProgressChart(st: SessionsPage.Stats) {
    val bars = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val line = MaterialTheme.colorScheme.tertiary
    val daily = st.dailySummary
    val progress = st.progressSummary
    val maxMinutes = (daily.maxOfOrNull { it.totalMinutes } ?: 1.0).coerceAtLeast(1.0)
    val keys = (daily.map { it.day } + progress.map { it.day }).distinct().sorted()
    if (keys.isEmpty()) return
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val slot = size.width / keys.size
            fun x(day: String) = slot * (keys.indexOf(day) + 0.5f)
            daily.forEach { d ->
                val h = size.height * (d.totalMinutes / maxMinutes).toFloat() / 1.1f
                drawRect(bars, Offset(x(d.day) - slot * 0.35f, size.height - h), androidx.compose.ui.geometry.Size(slot * 0.7f, h))
            }
            fun y(p: Double) = size.height - size.height * (p / 100).toFloat() / 1.1f
            for (i in 1 until progress.size) drawLine(line, Offset(x(progress[i - 1].day), y(progress[i - 1].endProgress)), Offset(x(progress[i].day), y(progress[i].endProgress)), 2.dp.toPx())
            progress.forEach { drawCircle(line, 3.dp.toPx(), Offset(x(it.day), y(it.endProgress))) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(keys.first(), keys.last()).distinct().forEach {
                Text(shortDay(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun shortDay(s: String): String {
    val d = parseDay(s) ?: return s
    return if (d == LocalDate.now()) "Today" else d.format(DateTimeFormatter.ofPattern("d MMM"))
}

@Composable
private fun DayHeader(day: DayBlock) {
    val today = LocalDate.now()
    val title = when (day.day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.day.format(DateTimeFormatter.ofPattern("EEEE")) }
    val full = day.day.format(DateTimeFormatter.ofPattern(if (day.day == today || day.day == today.minusDays(1)) "EEE, d MMM" else "d MMM"))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(full, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(hm(day.seconds), style = MaterialTheme.typography.labelLarge)
        Badge("${day.count}", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimeLabel(iso: String) {
    Box(Modifier.width(58.dp).height(30.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(parseInstant(iso)?.format(DateTimeFormatter.ofPattern("H:mm")).orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun readText(s: Double): String {
    val n = s.roundToInt()
    if (n < 60) return "$n sec"
    val m = n / 60
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

@Composable
private fun SessionRow(s: SessionsPage.Item, gap: Double?, longest: Double) {
    val src = bucket(s.source)
    val end = s.endProgress
    val start = if (end != null && s.progressDelta != null) end - s.progressDelta else null
    val moved = start != null && end != null && abs(start - end) >= 0.5
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimeLabel(s.startedAt)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Read ${readText(s.durationSeconds)}", style = MaterialTheme.typography.titleSmall)
                if (s.durationSeconds == longest && s.durationSeconds >= 60) Badge("Longest", MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.weight(1f))
                end?.let { Text("${it.roundToInt()}%", style = MaterialTheme.typography.titleSmall) }
            }
            Text(
                "${sourceLabels[src] ?: src} • " + when {
                    moved -> "${start!!.roundToInt()}% → ${end!!.roundToInt()}%"
                    end != null -> "at ${end.roundToInt()}%"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (end != null) {
                val track = MaterialTheme.colorScheme.outlineVariant
                val fill = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    val cy = size.height / 2
                    drawRoundRect(track, Offset(0f, cy - 4.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 8.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    if (moved) {
                        val a = (minOf(start!!, end) / 100).toFloat() * size.width
                        val w = maxOf(size.width * (abs(end - start) / 100).toFloat(), 6.dp.toPx())
                        drawRoundRect(fill.copy(alpha = 0.6f), Offset(a, cy - 4.dp.toPx()), androidx.compose.ui.geometry.Size(w, 8.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    }
                    drawCircle(fill, 6.dp.toPx(), Offset((size.width * (end / 100).toFloat()).coerceIn(6.dp.toPx(), size.width - 6.dp.toPx()), cy))
                }
            }
            if (gap != null && gap >= 60) Text("Gap ${hm(gap)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
