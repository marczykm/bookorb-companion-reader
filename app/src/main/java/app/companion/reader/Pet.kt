package app.companion.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The reading companions from the BookOrbit KOReader plugin. Sprite art is 64x64 pixel art with six
 * poses per character, shipped as assets/pets/<name>.json.
 */
object Pets {
    val NAMES = listOf(
        "cat", "dog", "rabbit", "owl", "penguin", "robot", "vampire", "dragon",
        "ghost", "mimic", "mummy", "witch", "skeleton", "candle", "alien",
    )
    private val cache = HashMap<String, Map<String, ImageBitmap>>()

    @Synchronized
    fun sprites(context: Context, pet: String): Map<String, ImageBitmap> = cache.getOrPut(pet) {
        val root = JSONObject(context.assets.open("pets/$pet.json").bufferedReader().use { it.readText() })
        val palette = root.getJSONObject("palette").let { p ->
            p.keys().asSequence().associate { it[0] to (0xFF000000.toInt() or p.getString(it).toInt(16)) }
        }
        val poses = root.getJSONObject("poses")
        poses.keys().asSequence().associateWith { pose ->
            val rows = poses.getJSONArray(pose)
            val pixels = IntArray(64 * 64) { i -> palette[rows.getString(i / 64)[i % 64]] ?: Color.TRANSPARENT }
            Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    private val MILESTONES = setOf(7, 30, 50, 100, 200, 365)

    /** Same rules as the plugin: one pose for the current situation. */
    fun pose(hasBook: Boolean, streak: Int, longest: Int, todayMin: Int, finishedToday: Boolean, hour: Int): String = when {
        !hasBook && streak == 0 -> "wait"
        todayMin <= 0 -> "sleep"
        finishedToday -> "ribbon"
        streak in MILESTONES || (streak > 1 && streak >= longest) -> "party"
        hour >= 22 || hour < 5 -> "yawn"
        else -> "read"
    }

    fun lines(pose: String, streak: Int, longest: Int, todayMin: Int): Pair<String, String> = when (pose) {
        "read" -> "Reading with you" to "${duration(todayMin)} today"
        "sleep" -> "Fast asleep" to "Wakes up when you read"
        "party" -> "$streak ${if (streak == 1) "day" else "days"} in a row!" to (if (streak >= longest) "A new best" else "Your best is $longest")
        "ribbon" -> "Book finished!" to ""
        "yawn" -> "Past bedtime" to "${duration(todayMin)} read today"
        else -> "Ready when you are" to "Open a book to start"
    }

    private fun duration(min: Int) = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

@Composable
fun PetCard(context: Context, data: Map<String, kotlinx.serialization.json.JsonObject>, hasBook: Boolean) {
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var pet by remember { mutableStateOf(prefs.getString("pet", "cat") ?: "cat") }
    var picking by remember { mutableStateOf(false) }

    val streak = data["reading-streak"]?.let { (it["currentStreak"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
    val longest = data["reading-streak"]?.let { (it["longestStreak"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
    val today = LocalDate.now().toString()
    val todayMin = (data["reading-rhythm"]?.get("days") as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        ?.lastOrNull { (it["date"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.startsWith(today) == true }
        ?.let { (it["readingSeconds"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
        ?.let { (it / 60).toInt() } ?: 0

    // A book counts as finished today when the year's completed count has grown since the day began.
    val completed = data["reading-goal"]?.let { (it["completedBooks"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
    val finishedToday = remember(completed, today) {
        if (completed == null) false else {
            if (prefs.getString("base_date", null) != today) {
                prefs.edit().putString("base_date", today).putInt("base_completed", completed).apply()
            }
            completed > prefs.getInt("base_completed", completed)
        }
    }

    val pose = Pets.pose(hasBook, streak, longest, todayMin, finishedToday, LocalDateTime.now().hour)
    val (line1, line2) = Pets.lines(pose, streak, longest, todayMin)

    val size = LocalWidgetSize.current
    val sprite: @Composable () -> Unit = {
        Image(
            bitmap = Pets.sprites(context, pet).getValue(pose), contentDescription = "Reading companion",
            filterQuality = FilterQuality.None, modifier = Modifier.size(when (size) { "s" -> 72.dp; "l" -> 152.dp; else -> 104.dp }),
        )
    }
    Card(Modifier.fillMaxWidth().fillMaxHeight().clickable { picking = true }) {
        if (size == "s") {
            // Half width: sprite above a single caption line.
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                sprite()
                Text(line1, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                sprite()
                Column {
                    Text(line1, style = MaterialTheme.typography.titleMedium)
                    if (line2.isNotEmpty()) Text(line2, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Tap to change companion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            title = { Text("Choose a companion") },
            text = {
                LazyVerticalGrid(columns = GridCells.Fixed(3), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Pets.NAMES) { name ->
                        Column(Modifier.clickable {
                            pet = name; prefs.edit().putString("pet", name).apply(); picking = false
                        }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(Pets.sprites(context, name).getValue("read"), name, filterQuality = FilterQuality.None, modifier = Modifier.size(72.dp))
                            Text(name.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
        )
    }
}
