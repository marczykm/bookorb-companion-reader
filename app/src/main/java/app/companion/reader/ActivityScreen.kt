package app.companion.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun long(seconds: Double): String {
    val s = seconds.roundToInt()
    if (s < 60) return "$s secs"
    val h = s / 3600
    val m = (s % 3600) / 60
    if (h == 0) return "$m min"
    val hours = if (h == 1) "1 hr" else "$h hrs"
    return if (m == 0) hours else "$hours, $m min"
}

private fun short(seconds: Double): String {
    val s = seconds.roundToInt()
    if (s < 60) return "${s}s"
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h == 0) "${m}m" else "${h}h ${m}m"
}

/** Insights and Achievements, switched with a segmented row. */
@Composable
fun ActivityScreen(app: App, onBack: () -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<ActivityOverview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var section by rememberSaveableInt(0)

    fun load() {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                overview = app.api.activityOverview()
            } catch (e: AuthException) {
                app.session.clearTokens(); onLogout()
            } catch (e: Exception) {
                error = e.message ?: "Failed to load"
            } finally {
                loading = false
            }
        }
    }
    BackgroundRefresh(app, failing = error != null, refresh = { load() })
    LaunchedEffect(Unit) { load() }

    SubScreen("Activity", onBack, actions = { OfflineIcon(app) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(section == 0, { section = 0 }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Insights") }
                SegmentedButton(section == 1, { section = 1 }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Achievements") }
            }
            if (section == 1) AchievementsPane(app, onLogout) else Box(Modifier.weight(1f).fillMaxWidth()) {
                overview?.let { o ->
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SnapshotCard(o)
                        DailyActivityCard(o.dailyActivity.days)
                        o.goal?.let { GoalCard(it) }
                        o.calendar?.let { CalendarCard(app, it) }
                        RhythmCard(o.rhythm)
                        CompletionCard(o.completion.months)
                        SourcesCard(o.sources)
                        PaceCard(o.pace)
                    }
                }
                if (loading && overview == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else if (overview == null && error == null) Text("No activity yet", Modifier.align(Alignment.Center))
                OfflineEmpty(app, hasContent = overview != null, failed = error != null)
            }
        }
    }
}

@Composable
private fun rememberSaveableInt(initial: Int) = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(initial) }.let {
    object : androidx.compose.runtime.MutableState<Int> {
        override var value: Int get() = it.intValue; set(v) { it.intValue = v }
        override fun component1() = value
        override fun component2(): (Int) -> Unit = { value = it }
    }
}

@Composable
private fun MediaBar(totals: ActivityDuration) {
    val total = (totals.readingSeconds + totals.listeningSeconds).coerceAtLeast(1.0)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outlineVariant)) {
            if (totals.readingSeconds > 0) Box(Modifier.weight(totals.readingSeconds.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            if (totals.listeningSeconds > 0) Box(Modifier.weight(totals.listeningSeconds.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
            if (total > totals.readingSeconds + totals.listeningSeconds) Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Reading ${long(totals.readingSeconds)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Listening ${long(totals.listeningSeconds)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Weekly snapshot ----

@Composable
private fun SnapshotCard(o: ActivityOverview) {
    val s = o.snapshot
    InfoCard("Weekly Snapshot") {
        Row(Modifier.fillMaxWidth()) {
            StatBlock(short(s.today.totalSeconds), "Today", Modifier.weight(1f))
            StatBlock(short(s.lastSevenDays.totalSeconds), "Last 7 days", Modifier.weight(1f))
            StatBlock("${s.currentStreak}", "Day streak", Modifier.weight(1f))
        }
        val before = s.previousSevenDays.totalSeconds
        if (before > 0) {
            val pct = ((s.lastSevenDays.totalSeconds - before) / before * 100).roundToInt()
            Text(if (pct >= 0) "Up $pct% vs last week" else "Down ${-pct}% vs last week", style = MaterialTheme.typography.titleSmall)
        }
        MediaBar(s.lastSevenDays)
    }
}

// ---- Daily activity ----

private data class DayBar(val label: String, val reading: Double, val listening: Double, val sessions: Int)

@Composable
private fun DailyActivityCard(days: List<ActivityDay>) {
    var range by remember { mutableIntStateOf(0) }   // 0 = 7D, 1 = 30D, 2 = 12W
    var selected by remember(range) { mutableStateOf<Int?>(null) }
    val data = remember(days, range) {
        val sorted = days.mapNotNull { d -> parseDay(d.day)?.let { it to d } }.sortedBy { it.first }
        if (range == 2) {
            val weeks = LinkedHashMap<LocalDate, DayBar>()
            for ((date, d) in sorted.takeLast(84)) {
                val start = date.minusDays(((date.dayOfWeek.value) % 7).toLong())   // Sunday first
                val cur = weeks[start]
                weeks[start] = DayBar(
                    "", (cur?.reading ?: 0.0) + d.readingSeconds, (cur?.listening ?: 0.0) + d.listeningSeconds, (cur?.sessions ?: 0) + d.sessionsCount,
                )
            }
            weeks.map { (start, b) -> b.copy(label = start.format(DateTimeFormatter.ofPattern("d MMM"))) }
        } else sorted.takeLast(if (range == 0) 7 else 30).map { (date, d) ->
            DayBar(date.format(DateTimeFormatter.ofPattern("d MMM")), d.readingSeconds, d.listeningSeconds, d.sessionsCount)
        }
    }
    val total = data.sumOf { it.reading + it.listening }
    val active = data.count { it.reading + it.listening > 0 }
    InfoCard("Daily Activity") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("7D", "30D", "12W").forEachIndexed { i, t -> SegmentedButton(range == i, { range = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock(long(total), "Total time")
            StatBlock(if (active > 0) long(total / active) else "–", if (range == 2) "Average per active week" else "Average per active day")
        }
        val max = (data.maxOfOrNull { it.reading + it.listening } ?: 0.0).coerceAtLeast(1e-9)
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(if (data.size > 14) 1.dp else 4.dp), verticalAlignment = Alignment.Bottom) {
            data.forEachIndexed { i, b ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { selected = i },
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    val h = 150.dp * (((b.reading + b.listening) / max).toFloat())
                    if (b.listening > 0) Box(Modifier.fillMaxWidth().height(h * (b.listening / (b.reading + b.listening)).toFloat()).background(MaterialTheme.colorScheme.secondary))
                    if (b.reading > 0) Box(Modifier.fillMaxWidth().height(h * (b.reading / (b.reading + b.listening)).toFloat())
                        .background(if (selected == i || (selected == null && i == data.lastIndex)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)))
                    if (b.reading + b.listening <= 0) Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("■ Reading", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("■ Listening", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        (selected?.let { data.getOrNull(it) } ?: data.lastOrNull())?.let { b ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text((if (range == 2) "Week of " else "") + b.label, style = MaterialTheme.typography.titleSmall)
                    Text(long(b.reading + b.listening), style = MaterialTheme.typography.titleSmall)
                }
                Text("${b.sessions} session${if (b.sessions == 1) "" else "s"}, Reading ${long(b.reading)}, Listening ${long(b.listening)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- Annual goal ----

@Composable
private fun GoalCard(goal: ActivityOverview.Goal) {
    InfoCard("Annual Goal") {
        val target = goal.goalBooks
        if (target != null && target > 0) {
            val primary = MaterialTheme.colorScheme.primary
            val track = MaterialTheme.colorScheme.outlineVariant
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = Stroke(12.dp.toPx(), cap = StrokeCap.Round)
                        val inset = 6.dp.toPx()
                        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(12.dp.toPx()))
                        drawArc(primary, -90f, 360f * (goal.completedBooks.toFloat() / target).coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = stroke)
                    }
                    Text("${goal.completedBooks}/$target", style = MaterialTheme.typography.titleMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (goal.status) {
                        "ahead" -> "↗ Ahead"
                        "on_pace" -> "＝ On pace"
                        "behind" -> "↘ Behind"
                        else -> null
                    }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text("Projected: ${"%.1f".format(goal.projectedBooks)} books", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (goal.points.isNotEmpty()) {
                val actual = MaterialTheme.colorScheme.primary
                val plan = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.fillMaxWidth().height(130.dp)) {
                    val maxY = (goal.points.maxOf { maxOf(it.actualCumulative, it.targetCumulative ?: 0.0) }).coerceAtLeast(1.0).toFloat()
                    val n = goal.points.size
                    fun pt(i: Int, v: Double) = Offset(if (n == 1) 0f else size.width * i / (n - 1), size.height - 8.dp.toPx() - (size.height - 16.dp.toPx()) * (v.toFloat() / maxY))
                    for (i in 1 until n) {
                        drawLine(actual, pt(i - 1, goal.points[i - 1].actualCumulative), pt(i, goal.points[i].actualCumulative), 3.dp.toPx())
                        val a = goal.points[i - 1].targetCumulative; val b = goal.points[i].targetCumulative
                        if (a != null && b != null) drawLine(plan, pt(i - 1, a), pt(i, b), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    }
                    goal.points.forEachIndexed { i, p -> drawCircle(actual, 3.dp.toPx(), pt(i, p.actualCumulative)) }
                }
            }
        } else {
            Text("${goal.completedBooks} read in ${goal.year}", style = MaterialTheme.typography.titleMedium)
            Text("No goal set", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Calendar ----

@Composable
private fun CalendarCard(app: App, initial: ActivityCalendar) {
    val scope = rememberCoroutineScope()
    var calendar by remember { mutableStateOf<ActivityCalendar?>(null) }
    var month by remember { mutableIntStateOf(LocalDate.now().monthValue) }
    var menu by remember { mutableStateOf(false) }
    val cal = calendar ?: initial
    val byDay = remember(cal) { cal.days.associate { it.day.take(10) to it.totalSeconds } }

    @Composable
    fun shade(seconds: Double): Color {
        val c = MaterialTheme.colorScheme.primary
        return when {
            seconds / 60 < 0.5 -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            seconds / 60 < 15.5 -> c.copy(alpha = 0.25f)
            seconds / 60 < 30.5 -> c.copy(alpha = 0.45f)
            seconds / 60 < 60.5 -> c.copy(alpha = 0.7f)
            else -> c
        }
    }

    InfoCard("Reading Calendar") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Consistency by day", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                TextButton(onClick = { menu = true }) { Text("${cal.year} ▾") }
                DropdownMenu(menu, { menu = false }) {
                    cal.availableYears.sortedDescending().forEach { y ->
                        DropdownMenuItem(text = { Text("$y") }, onClick = {
                            menu = false
                            scope.launch {
                                runCatching { app.api.activityCalendar(y) }.getOrNull()?.let {
                                    calendar = it
                                    month = if (y == LocalDate.now().year) LocalDate.now().monthValue else 1
                                }
                            }
                        })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = maxOf(month - 1, 1) }, enabled = month > 1) { Text("‹") }
            Text("${monthName(month)} ${cal.year}".replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { month = minOf(month + 1, 12) }, enabled = month < 12) { Text("›") }
        }
        val first = YearMonth.of(cal.year, month)
        val offset = first.atDay(1).dayOfWeek.value % 7   // Sunday first
        val cells = List(offset) { null } + (1..first.lengthOfMonth()).toList()
        Row(Modifier.fillMaxWidth()) {
            "SMTWTFS".forEach { Text("$it", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val day = week.getOrNull(i)
                    Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp)).then(
                        if (day != null) Modifier.background(shade(byDay["%04d-%02d-%02d".format(cal.year, month, day)] ?: 0.0)) else Modifier,
                    ), contentAlignment = Alignment.Center) { if (day != null) Text("$day", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Text("Daily time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("No activity" to 0.0, "1 to 15 minutes" to 600.0, "16 to 30 minutes" to 1200.0, "31 to 60 minutes" to 2700.0, "More than 60 minutes" to 5400.0).chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { (label, secs) ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(shade(secs)))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---- Rhythm ----

@Composable
private fun RhythmCard(r: ActivityOverview.Rhythm) {
    var hours by remember { mutableStateOf(false) }
    val dayShort = remember { (0..6).map { DayOfWeek.of(if (it == 0) 7 else it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) } }
    val dayFull = remember { (0..6).map { DayOfWeek.of(if (it == 0) 7 else it).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) } }
    InfoCard("Reading Rhythm") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!hours, { hours = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Days") }
            SegmentedButton(hours, { hours = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Hours") }
        }
        if (hours) {
            val byHour = r.hours.associate { it.hour to it.totalSeconds / 60 }
            BarChart((0..23).map { byHour[it] ?: 0.0 }, (0..23).map { if (it % 3 == 0) "$it" else "" }, height = 120.dp, highlight = r.peakHour)
        } else {
            val byDay = r.weekdays.associate { it.dayOfWeek to it.averageSeconds / 60 }
            BarChart((0..6).map { byDay[it] ?: 0.0 }, dayShort.map { it.take(3) }, height = 120.dp, highlight = r.favoriteDayOfWeek)
        }
        r.favoriteDayOfWeek?.let { d ->
            Text("${dayFull[d.coerceIn(0, 6)]}${r.peakHour?.let { " around $it" } ?: ""} is your strongest reading window.", style = MaterialTheme.typography.titleSmall)
        }
    }
}

// ---- Completion ----

@Composable
private fun CompletionCard(months: List<ActivityOverview.Completion.Month>) {
    var range by remember { mutableIntStateOf(0) }   // 0 = 1Y, 1 = 5Y, 2 = All
    val bars = remember(months, range) {
        val now = LocalDate.now()
        if (range == 0) (11 downTo 0).map { back ->
            val d = now.minusMonths(back.toLong())
            monthShort(d.monthValue).let { _ -> d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) } to
                (months.firstOrNull { it.year == d.year && it.month == d.monthValue }?.count ?: 0)
        } else {
            val years = if (range == 1) (now.year - 4..now.year).toList() else ((months.minOfOrNull { it.year } ?: now.year)..now.year).toList()
            years.map { y -> "$y" to months.filter { it.year == y }.sumOf { it.count } }
        }
    }
    InfoCard("Completion") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("1Y", "5Y", "All").forEachIndexed { i, t -> SegmentedButton(range == i, { range = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) } }
        }
        val best = bars.maxByOrNull { it.second }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Completed", style = MaterialTheme.typography.titleMedium)
            if (best != null && best.second > 0) Text("Best: ${best.first} · ${best.second}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BarChart(bars.map { it.second.toDouble() }, bars.map { it.first.take(if (range == 0) 3 else 4) }, height = 110.dp)
    }
}

// ---- Sources ----

private val sourceNames = mapOf(
    "bookorbit" to "BookOrbit", "ios" to "iOS app", "watchos" to "Apple Watch", "android" to "Android app", "koreader" to "KOReader", "kobo" to "Kobo",
)

@Composable
private fun SourcesCard(s: ActivityOverview.Sources) {
    val slices = s.slices.filter { it.totalSeconds > 0 }
    val total = slices.sumOf { it.totalSeconds }.coerceAtLeast(1.0)
    val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
    InfoCard("Where You Read") {
        Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(50)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            slices.forEachIndexed { i, sl -> Box(Modifier.weight(sl.totalSeconds.toFloat().coerceAtLeast((total * 0.02).toFloat())).fillMaxHeight().background(palette[i % palette.size])) }
        }
        slices.forEachIndexed { i, sl ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(palette[i % palette.size]))
                Text(sourceNames[sl.bucket] ?: sl.bucket, Modifier.weight(1f))
                Text(long(sl.totalSeconds), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${(sl.totalSeconds / total * 100).roundToInt()}%", Modifier.width(44.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        MediaBar(s.totals)
    }
}

// ---- Pace ----

@Composable
private fun PaceCard(p: ActivityOverview.Pace) {
    InfoCard("Reading Pace") {
        Row(Modifier.fillMaxWidth()) {
            StatBlock(p.medianDurationSeconds?.let { short(it) } ?: "–", "Typical session", Modifier.weight(1f))
            StatBlock(p.medianProgressDelta?.let { "${it.roundToInt()}%" } ?: "–", "Median progress", Modifier.weight(1f))
        }
        Text("Based on ${p.eligibleSessions} eligible sessions", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
