package app.companion.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull

/** Dashboard widgets requested from the server, in display order. */
val WIDGET_TYPES = listOf(
    "reading-streak", "reading-goal", "reading-rhythm", "year-projection",
    "monthly-challenge", "highlight-of-the-day", "library-overview", "long-wait",
)

private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.int(key: String) = num(key)?.toInt()
private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

/** The size the widget being drawn was given: "s" is half width, "l" shows more. */
val LocalWidgetSize = androidx.compose.runtime.compositionLocalOf { "m" }

/** One Home widget by id; draws nothing when the server had no data for it. */
@Composable
fun WidgetItem(id: String, data: Map<String, JsonObject>, hasBook: Boolean, size: String = "m") =
    androidx.compose.runtime.CompositionLocalProvider(LocalWidgetSize provides size) {
    when (id) {
        "clock" -> ClockCard()
        "pet" -> PetCard(androidx.compose.ui.platform.LocalContext.current, data, hasBook)
        "reading-streak" -> data[id]?.let { StreakCard(it, Modifier) }
        "reading-goal" -> data[id]?.let { GoalCard(it, Modifier) }
        "reading-rhythm" -> data[id]?.let { RhythmCard(it) }
        "year-projection" -> data[id]?.let { ProjectionCard(it) }
        "monthly-challenge" -> data[id]?.let { ChallengeCard(it) }
        "highlight-of-the-day" -> data[id]?.let { HighlightCard(it) }
        "long-wait" -> data[id]?.let { LongWaitCard(it) }
        "library-overview" -> data[id]?.let { OverviewCard(it) }
    }
    }

@Composable
private fun WidgetCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // fillMaxHeight lets two cards sharing a row grow to the taller one; on its own it keeps its natural height.
    Card(modifier.fillMaxWidth().fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), border = Display.cardBorder()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** The time and date, in the phone's own 12 or 24 hour format. It needs nothing from the server. */
@Composable
private fun ClockCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(java.time.ZonedDateTime.now()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = java.time.ZonedDateTime.now()
            // Wake up just after the next minute starts.
            kotlinx.coroutines.delay((60 - now.second) * 1000L - now.nano / 1_000_000 + 50)
        }
    }
    val size = LocalWidgetSize.current
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    val time = now.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    val date = now.format(java.time.format.DateTimeFormatter.ofLocalizedDate(
        if (size == "s") java.time.format.FormatStyle.MEDIUM else java.time.format.FormatStyle.FULL,
    ))
    WidgetCard("Clock") {
        Text(
            time,
            style = when (size) { "s" -> MaterialTheme.typography.headlineMedium; "l" -> MaterialTheme.typography.displayLarge; else -> MaterialTheme.typography.displayMedium },
        )
        Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StreakCard(d: JsonObject, modifier: Modifier) = WidgetCard("Reading streak", modifier) {
    val current = d.int("currentStreak") ?: 0
    Text("$current day${if (current == 1) "" else "s"}", style = MaterialTheme.typography.headlineSmall)
    val days = (d["lastSevenDays"] as? JsonArray)?.map { (it as? JsonPrimitive)?.booleanOrNull == true }.orEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEach { read ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(
                if (read) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
    if (LocalWidgetSize.current != "s") d.int("longestStreak")?.let { Text("Longest: $it", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun GoalCard(d: JsonObject, modifier: Modifier) = WidgetCard("${d.int("year") ?: ""} goal", modifier) {
    val done = d.int("completedBooks") ?: 0
    val goal = d.int("goalBooks")
    if (goal == null || goal == 0) {
        Text("$done read", style = MaterialTheme.typography.headlineSmall)
        Text("No goal set", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("$done / $goal", style = MaterialTheme.typography.headlineSmall)
        LinearProgressIndicator(progress = { (done.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("books", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RhythmCard(d: JsonObject) = WidgetCard("Reading rhythm") {
    val seconds = (d["days"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.num("readingSeconds") }.orEmpty()
    if (seconds.isNotEmpty()) {
        val max = (seconds.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
        val chartHeight = when (LocalWidgetSize.current) { "s" -> 40.dp; "l" -> 96.dp; else -> 48.dp }
        Row(Modifier.fillMaxWidth().height(chartHeight), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            seconds.forEach { s ->
                Box(Modifier.weight(1f).fillMaxHeight((s / max).toFloat().coerceIn(0.04f, 1f))
                    .clip(RoundedCornerShape(2.dp)).background(
                        if (s > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
    val avgMin = ((d.num("avgSecondsPerDay") ?: 0.0) / 60).toInt()
    Text(if (LocalWidgetSize.current == "s") "$avgMin min/day" else "$avgMin min/day on average · ${d.int("consistencyPercent") ?: 0}% of days read",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ProjectionCard(d: JsonObject) = WidgetCard("On pace for ${java.time.Year.now().value}") {
    Text("${d.int("projectedBooks") ?: 0} books", style = MaterialTheme.typography.headlineSmall)
    val trend = when (d.str("trend")) { "up" -> "trending up"; "down" -> "trending down"; else -> "steady" }
    if (LocalWidgetSize.current != "s") Text("${d.int("booksCompletedYtd") ?: 0} done so far · about ${d.int("projectedHours") ?: 0} h of reading · $trend",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ChallengeCard(d: JsonObject) = WidgetCard("Monthly challenge") {
    Text(d.str("title") ?: "", style = MaterialTheme.typography.titleMedium)
    if (LocalWidgetSize.current != "s") d.str("description")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    val progress = d.num("progress") ?: 0.0
    val target = (d.num("target") ?: 1.0).coerceAtLeast(1.0)
    LinearProgressIndicator(progress = { (progress / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    val completed = (d["completed"] as? JsonPrimitive)?.booleanOrNull == true
    Text(if (completed) "Completed" else "${progress.toInt()} / ${target.toInt()}", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun HighlightCard(d: JsonObject) = WidgetCard("Highlight of the day") {
    Text("“${d.str("text").orEmpty()}”", maxLines = when (LocalWidgetSize.current) { "s" -> 3; "l" -> 14; else -> 6 },
        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    d.str("bookTitle")?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun LongWaitCard(d: JsonObject) = WidgetCard("Waiting the longest") {
    Text(d.str("title") ?: "Untitled", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text("On your shelf for ${d.int("waitingDays") ?: 0} days", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun OverviewCard(d: JsonObject) = WidgetCard("Library") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("Books" to "totalBooks", "Authors" to "totalAuthors", "Series" to "totalSeries", "This year" to "booksAddedThisYear")
            .let { if (LocalWidgetSize.current == "s") it.take(2) else it }
            .forEach { (label, key) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${d.int(key) ?: 0}", style = MaterialTheme.typography.titleLarge)
                    Text(label, style = MaterialTheme.typography.labelSmall)
                }
            }
    }
}
