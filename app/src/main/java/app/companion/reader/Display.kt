package app.companion.reader

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.dp

/** The kind of screen the device has. E-ink screens need outlines to tell widgets apart, and mono ones no colour. */
enum class ScreenType(val label: String, val note: String) {
    Normal("Normal screen", "Colour LCD or OLED. The app looks as designed."),
    EinkColor("E-ink, colour", "Adds outlines around widgets so they stand out on the paper-like screen."),
    EinkMono("E-ink, black and white", "Outlines around widgets, and everything in shades of grey."),
}

/** The chosen screen type, kept on the device. Reading `type` in a composable makes it follow changes. */
object Display {
    private const val KEY = "screenType"
    var type by mutableStateOf(ScreenType.Normal)
        private set

    fun load(context: Context) {
        val saved = context.getSharedPreferences("display", Context.MODE_PRIVATE).getString(KEY, null)
        type = ScreenType.values().firstOrNull { it.name == saved } ?: ScreenType.Normal
    }

    fun set(context: Context, value: ScreenType) {
        type = value
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
    }

    val mono get() = type == ScreenType.EinkMono
    val bordered get() = type != ScreenType.Normal

    /** Greys out cover art and sprites on a black and white screen. */
    val imageFilter: ColorFilter?
        get() = if (mono) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null

    /** The outline of a widget card, or none on a normal screen. */
    @Composable
    fun cardBorder(): BorderStroke? = if (bordered) BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface) else null

    /** The purple "syncing" dot, dark grey on a black and white screen. */
    val dotColor get() = if (mono) 0xFF000000.toInt() else 0xFF6750A4.toInt()
}

private val grey = Color(0xFFD6D6D6)
private val monoColors = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = grey, onPrimaryContainer = Color.Black,
    secondary = Color(0xFF333333), onSecondary = Color.White,
    secondaryContainer = grey, onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF333333), onTertiary = Color.White,
    tertiaryContainer = grey, onTertiaryContainer = Color.Black,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color.White, onSurfaceVariant = Color(0xFF222222),
    surfaceTint = Color.Black,
    outline = Color.Black, outlineVariant = Color(0xFF9E9E9E),
    error = Color.Black, onError = Color.White,
    errorContainer = grey, onErrorContainer = Color.Black,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White, surfaceContainerHighest = Color.White,
)

/** The app's colours for the chosen screen type. */
@Composable
fun displayColors() = if (Display.mono) monoColors else lightColorScheme()
