package app.companion.reader

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
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

    /** An outline for dialogs drawn by Compose, on an e-ink screen. */
    @Composable
    fun Modifier.dialogBorder(shape: androidx.compose.ui.graphics.Shape): Modifier =
        if (bordered) border(2.dp, MaterialTheme.colorScheme.onSurface, shape) else this

    /** Bottom sheets are square-topped on e-ink, with a line along the top edge where the sheet begins. */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    fun sheetShape(): androidx.compose.ui.graphics.Shape =
        if (bordered) androidx.compose.ui.graphics.RectangleShape else androidx.compose.material3.BottomSheetDefaults.ExpandedShape

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    fun SheetHandle() {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            if (bordered) androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            androidx.compose.material3.BottomSheetDefaults.DragHandle()
        }
    }

    /** A classic dialog builder; on a black and white screen its controls are black too. */
    fun dialogBuilder(context: Context) =
        if (mono) androidx.appcompat.app.AlertDialog.Builder(context, R.style.Dialog_Mono)
        else androidx.appcompat.app.AlertDialog.Builder(context)

    /** The same outline for a classic Android dialog, once it is showing. */
    fun frame(dialog: androidx.appcompat.app.AlertDialog): androidx.appcompat.app.AlertDialog {
        if (!bordered) return dialog
        val ctx = dialog.context
        val fill = android.util.TypedValue().let { tv ->
            if (ctx.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) tv.data else 0xFFFFFFFF.toInt()
        }
        val dark = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val density = ctx.resources.displayMetrics.density
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = 12 * density
            setStroke((2 * density).toInt(), if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        })
        return dialog
    }

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
