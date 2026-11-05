package app.companion.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ---- Rarity styling ----

/** Light and dark tone of a rarity; plain greys on a black and white screen. */
private fun rarityColors(rarity: String): Pair<Color, Color> =
    if (Display.mono) when (rarity) {
        "rare" -> Color(0xFF9A9A9A) to Color(0xFF4A4A4A)
        "epic" -> Color(0xFF777777) to Color(0xFF2E2E2E)
        "legendary" -> Color(0xFF555555) to Color(0xFF000000)
        else -> Color(0xFFCCCCCC) to Color(0xFF858585)
    } else when (rarity) {
        "rare" -> Color(0xFF73ADF2) to Color(0xFF2E59A6)
        "epic" -> Color(0xFFB38CF2) to Color(0xFF6140B3)
        "legendary" -> Color(0xFFFAD666) to Color(0xFFB88519)
        else -> Color(0xFFD1D1D1) to Color(0xFF858585)
    }

private fun rarityLabel(r: String) = r.replaceFirstChar(Char::uppercase)

/** Maps the server's icon names (Lucide-style) to Material icons; anything unknown becomes a medal. */
private fun symbol(name: String): ImageVector = when (name.lowercase()) {
    "flame" -> Icons.Default.LocalFireDepartment
    "calendar", "calendar-check" -> Icons.Default.CalendarMonth
    "compass" -> Icons.Default.Explore
    "globe" -> Icons.Default.Public
    "book-open", "book", "book-marked" -> Icons.Default.MenuBook
    "clock", "timer", "hourglass" -> Icons.Default.Schedule
    "gauge", "activity" -> Icons.Default.Speed
    "moon", "moon-star" -> Icons.Default.DarkMode
    "sunrise", "sun" -> Icons.Default.WbSunny
    "fast-forward", "rabbit", "zap" -> Icons.Default.Bolt
    "heart" -> Icons.Default.Favorite
    "play" -> Icons.Default.PlayArrow
    "library", "layers" -> Icons.Default.LibraryBooks
    "pen", "pencil", "pen-line" -> Icons.Default.Edit
    "file-text", "list-ordered" -> Icons.Default.Description
    "bookmark" -> Icons.Default.Bookmark
    "hammer" -> Icons.Default.Build
    "star", "sparkles" -> Icons.Default.Star
    "palette" -> Icons.Default.Palette
    "trophy" -> Icons.Default.EmojiEvents
    "repeat", "refresh-cw", "arrow-left-right", "arrow-up-down" -> Icons.Default.Repeat
    "shield" -> Icons.Default.Shield
    "smartphone" -> Icons.Default.Smartphone
    "target" -> Icons.Default.TrackChanges
    "coffee" -> Icons.Default.Coffee
    else -> Icons.Default.WorkspacePremium
}

// ---- Grouping ----

/** One badge on screen: a single achievement, or a set of tiers shown as its current tier. */
private data class BadgeModel(
    val id: String, val name: String, val icon: String, val rarity: String, val earned: Boolean, val secret: Boolean,
    val caption: String, val fraction: Double, val next: AchievementItem?,
)

private fun num(v: Double?): String = v?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else "%g".format(it) } ?: ""

private fun parseDate(iso: String?): LocalDate? =
    iso?.let { s -> runCatching { OffsetDateTime.parse(s).toLocalDate() }.getOrNull() ?: parseDay(s) }

private fun dateText(iso: String?): String? = parseDate(iso)?.format(DateTimeFormatter.ofPattern("d MMM"))

private fun badges(items: List<AchievementItem>): List<BadgeModel> {
    val order = mutableListOf<String>()
    val groups = HashMap<String, MutableList<AchievementItem>>()
    for (it in items.sortedBy { it.sortOrder ?: 0 }) {
        val k = it.groupKey ?: it.key
        if (k !in groups) order.add(k)
        groups.getOrPut(k) { mutableListOf() }.add(it)
    }
    return order.map { k ->
        val tiers = groups.getValue(k).sortedBy { it.tier ?: 0 }
        val top = tiers.lastOrNull { it.earned }
        val next = tiers.firstOrNull { !it.earned }
        val shown = top ?: tiers[0]
        val hasTiers = tiers.size > 1
        val secret = top == null && tiers.all { it.hidden }
        var caption = "Not started"
        var fraction = 0.0
        val nt = next?.threshold; val np = next?.currentProgress
        if (nt != null && nt > 0 && np != null) fraction = (np / nt).coerceIn(0.0, 1.0)
        if (secret) caption = "Keep reading"
        else if (hasTiers) {
            val parts = mutableListOf<String>()
            top?.tier?.let { parts.add("Tier $it") }
            if (nt != null && np != null && (np > 0 || top != null)) parts.add("${num(np)} / ${num(nt)}")
            if (parts.isNotEmpty()) caption = parts.joinToString(" · ")
        } else {
            val t = tiers[0]
            if (t.earned) caption = dateText(t.awardedAt) ?: "Earned"
            else if (t.threshold != null && t.currentProgress != null && t.currentProgress > 0) caption = "${num(t.currentProgress)} / ${num(t.threshold)}"
        }
        BadgeModel(k, if (secret) "Secret achievement" else shown.name, shown.iconName, shown.rarity, top != null, secret, caption, fraction, next)
    }
}

/** The next tiers still to earn, closest first. */
private fun closest(c: AchievementCatalogue): List<Pair<AchievementItem, Double>> =
    c.categories.flatMap { badges(it.achievements) }.mapNotNull { b ->
        val n = b.next ?: return@mapNotNull null
        val t = n.threshold; val p = n.currentProgress
        if (n.hidden || t == null || t <= 0 || p == null || p <= 0) null else n to (p / t).coerceAtMost(1.0)
    }.sortedByDescending { it.second }

// ---- Medal ----

@Composable
private fun Medal(icon: String, rarity: String, earned: Boolean, secret: Boolean = false, fraction: Double = 0.0, size: Dp = 72.dp) {
    val (light, dark) = rarityColors(rarity)
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val medal = size
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (earned) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(listOf(light, dark))))
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White.copy(alpha = 0.45f), radius = medal.toPx() * 0.44f, style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.12f), radius = medal.toPx() * 0.3f)
            }
            Icon(symbol(icon), null, Modifier.size(size * 0.46f), tint = Color.White)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(grey.copy(alpha = 0.18f))
                drawCircle(grey.copy(alpha = 0.4f), radius = this.size.minDimension * 0.45f, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f))))
                if (fraction > 0) drawArc(light, -90f, 360f * fraction.toFloat(), false, Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
                    Size(this.size.width - 3.dp.toPx(), this.size.height - 3.dp.toPx()), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            if (secret) Text("?", style = MaterialTheme.typography.titleLarge, color = grey)
            else Icon(symbol(icon), null, Modifier.size(size * 0.44f), tint = grey.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun RarityPill(rarity: String) {
    Badge(rarityLabel(rarity), rarityColors(rarity).let { if (Display.mono) it.second else it.first })
}

// ---- Pane ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsPane(app: App, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var catalogue by remember { mutableStateOf<AchievementCatalogue?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    fun load() {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                catalogue = app.api.achievements()
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

    Box(Modifier.fillMaxSize()) {
        catalogue?.let { c ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { LatestUnlock(c.categories.flatMap { it.achievements }) }
                item { SummaryCard(c) }
                item { ClosestSection(c) { showAll = true } }
                items(c.categories, key = { it.key }) { CategoryCard(it) }
            }
            if (showAll) ModalBottomSheet(
                onDismissRequest = { showAll = false }, shape = Display.sheetShape(), dragHandle = { Display.SheetHandle() },
            ) {
                Text("Closest to unlocking", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(closest(c), key = { it.first.key }) { (a, f) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Medal(a.iconName, a.rarity, false, fraction = f, size = 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = MaterialTheme.typography.titleSmall)
                                Text("${num(a.currentProgress ?: 0.0)} / ${num(a.threshold ?: 0.0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${(f * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        if (loading && catalogue == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        else if (catalogue == null && error == null) Text("No achievements yet", Modifier.align(Alignment.Center))
        OfflineEmpty(app, hasContent = catalogue != null, failed = error != null)
    }
}

@Composable
private fun LatestUnlock(items: List<AchievementItem>) {
    val earned = items.filter { it.earned && parseDate(it.awardedAt) != null }.sortedByDescending { parseDate(it.awardedAt) }
    val recent = earned.take(7)
    if (recent.isEmpty()) return
    val now = LocalDate.now()
    val thisMonth = earned.count { parseDate(it.awardedAt)!!.let { d -> d.year == now.year && d.month == now.month } }
    val pager = rememberPagerState { recent.size }
    InfoCard(null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("LATEST UNLOCK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$thisMonth in ${monthName(now.monthValue)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalPager(pager, Modifier.fillMaxWidth()) { i ->
            val a = recent[i]
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Medal(a.iconName, a.rarity, true, size = 110.dp)
                Text(a.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RarityPill(a.rarity)
                    Text("${a.category.replaceFirstChar(Char::uppercase)} · ${dateText(a.awardedAt).orEmpty()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(a.description, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (recent.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(recent.size) { i ->
                Box(Modifier.padding(3.dp).size(7.dp).clip(CircleShape).background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

@Composable
private fun SummaryCard(c: AchievementCatalogue) {
    val all = c.categories.flatMap { it.achievements }
    InfoCard(null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.2f)) {
                Text("${c.totalEarned}", style = MaterialTheme.typography.displaySmall)
                Text("of ${c.totalAvailable} tiers", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            listOf("common", "rare", "epic", "legendary").forEach { r ->
                val of = all.filter { it.rarity == r }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Medal("", r, true, size = 30.dp)
                    Text("${of.count { it.earned }}/${of.size}", style = MaterialTheme.typography.labelLarge)
                    Text(rarityLabel(r), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ClosestSection(c: AchievementCatalogue, onAll: () -> Unit) {
    val list = closest(c)
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Closest to unlocking", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAll) { Text("All ${list.size} ›") }
        }
        list.take(3).forEach { (a, f) ->
            InfoCard(null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Medal(a.iconName, a.rarity, false, fraction = f, size = 66.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RarityPill(a.rarity)
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        Text(a.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val t = a.threshold; val p = a.currentProgress
                        if (t != null && p != null) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(num((t - p).coerceAtLeast(0.0)), style = MaterialTheme.typography.titleLarge)
                                Text("to go", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(progress = { f.toFloat() }, Modifier.weight(1f))
                                Text("${num(p)} / ${num(t)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun categoryIcon(key: String): ImageVector = when (key) {
    "reading" -> Icons.Default.MenuBook
    "library" -> Icons.Default.LibraryBooks
    "exploration" -> Icons.Default.Explore
    "dedication" -> Icons.Default.LocalFireDepartment
    "devices" -> Icons.Default.Smartphone
    else -> Icons.Default.WorkspacePremium
}

@Composable
private fun CategoryCard(category: AchievementCategory) {
    val list = remember(category) { badges(category.achievements) }
    val pct = if (category.totalCount > 0) category.earnedCount.toDouble() / category.totalCount else 0.0
    InfoCard(null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(categoryIcon(category.key), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(category.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Badge("${category.earnedCount} / ${category.totalCount}", MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${(pct * 100).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { pct.toFloat() }, Modifier.width(44.dp))
        }
        list.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                for (i in 0 until 3) {
                    val b = row.getOrNull(i)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (b != null) {
                            Medal(b.icon, b.rarity, b.earned, b.secret, b.fraction)
                            Text(b.name, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(b.caption, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
