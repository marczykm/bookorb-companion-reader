package app.companion.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A book cover from the server, with a plain block behind it while it loads or when there is none. */
@Composable
fun Cover(app: App, bookId: Long?, modifier: Modifier = Modifier, round: Boolean = false) {
    val shape = if (round) androidx.compose.foundation.shape.CircleShape else MaterialTheme.shapes.small
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (bookId != null) {
            AsyncImage(
                model = app.api.thumbnailUrl(bookId), contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = Display.imageFilter, modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** A page opened from a list: a top bar with a back arrow above [content]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = actions,
        )
    }, content = content)
}

/** A rounded label, outlined in its colour. */
@Composable
fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(50), color = androidx.compose.ui.graphics.Color.Transparent, border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun StatBlock(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A card used by the statistics pages, outlined for e-ink screens. */
@Composable
fun InfoCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), border = Display.cardBorder(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A row of vertical bars scaled to the largest value, each with a small label underneath. */
@Composable
fun BarChart(values: List<Double>, labels: List<String>, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 64.dp, highlight: Int? = null) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(height), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().height(if (v > 0) (height * (v / max).toFloat()).coerceAtLeast(6.dp) else 2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    v <= 0 -> MaterialTheme.colorScheme.outlineVariant
                                    highlight == i -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                },
                            ),
                    )
                }
                Text(labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** "1h 20m" for long spans, whole minutes below an hour. */
fun formatSeconds(seconds: Double): String {
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${maxOf(m, if (total > 0) 1 else 0)}m"
    }
}

/** Hours only above an hour, minutes below: the short form used on month headers. */
fun shortDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h" else "${maxOf(m, 1)}m"
}

fun monthName(month: Int): String = java.time.Month.of(month.coerceIn(1, 12)).getDisplayName(TextStyle.FULL, Locale.getDefault())
fun monthShort(month: Int): String = java.time.Month.of(month.coerceIn(1, 12)).getDisplayName(TextStyle.NARROW, Locale.getDefault())

/** The date part of a server timestamp. */
fun parseDay(s: String?): LocalDate? = s?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.substring(0, 10)) }.getOrNull() }

/** A server timestamp in the phone's time zone, whether it carries a zone or only a date. */
fun parseInstant(iso: String?): java.time.ZonedDateTime? = iso?.let { s ->
    runCatching { java.time.OffsetDateTime.parse(s).atZoneSameInstant(java.time.ZoneId.systemDefault()) }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()) }.getOrNull()
        ?: parseDay(s)?.atStartOfDay(java.time.ZoneId.systemDefault())
}

private val fullDate = DateTimeFormatter.ofPattern("d MMM yyyy")
fun fullDate(iso: String?): String? = parseInstant(iso)?.format(fullDate)

/** "1h 05m" style used on the book page; seconds below a minute. */
fun hm(seconds: Double): String {
    val s = seconds.toInt()
    if (s < 60) return "${s}s"
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h == 0) "${m}m" else "${h}h %02dm".format(m)
}

/** Description text as plain words: tags and common entities removed. */
fun plainText(html: String): String = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
    .replace(Regex("\\s+"), " ").trim()
