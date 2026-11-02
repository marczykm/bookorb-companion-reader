package app.companion.reader

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

const val DEFAULT_WORDS_PER_MINUTE = 230.0

/** How the book is drawn, kept on the device and shared by every book. */
class ReaderSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /** Multiplier of the publisher's size, 1.0 = 100%. */
    var fontSize: Double
        get() = prefs.getFloat("fontSize", 1.0f).toDouble()
        set(v) = prefs.edit().putFloat("fontSize", v.toFloat()).apply()

    /** Multiplier of the default page margins, 1.0 = default, 0 = none. */
    var margins: Double
        get() = prefs.getFloat("margins", 1.0f).toDouble()
        set(v) = prefs.edit().putFloat("margins", v.toFloat()).apply()

    /** Volume up turns back a page and volume down turns forward. */
    var volumeKeys: Boolean
        get() = prefs.getBoolean("volumeKeys", true)
        set(v) = prefs.edit().putBoolean("volumeKeys", v).apply()

    /** Words per minute learned from the user's own reading; 0 until there is enough to go on. */
    var learnedPace: Double
        get() = prefs.getFloat("learnedPace", 0f).toDouble()
        set(v) = prefs.edit().putFloat("learnedPace", v.toFloat()).apply()

    /** Lines are stretched to both edges, with hyphenation so the gaps stay small. */
    var justify: Boolean
        get() = prefs.getBoolean("justify", false)
        set(v) = prefs.edit().putBoolean("justify", v).apply()

    /** "light" or "dark", switched with the day/night button next to Aa. */
    var theme: String
        get() = if (prefs.getString("theme", "light") == "dark") "dark" else "light"
        set(v) = prefs.edit().putString("theme", v).apply()

    val effectiveTheme: String get() = theme

    fun toPreferences() = EpubPreferences(
        fontSize = fontSize,
        pageMargins = margins,
        // Alignment is ignored while the book's own styles apply, so they step aside only when justifying.
        textAlign = if (justify) TextAlign.JUSTIFY else null,
        hyphens = if (justify) true else null,
        publisherStyles = if (justify) false else null,
        theme = when (effectiveTheme) { "dark" -> Theme.DARK; else -> Theme.LIGHT },
    )
}

/** The "Aa" dialog: font size, margins and justify, applied as you press. */
fun showReaderSettings(host: Context, settings: ReaderSettings, onChange: () -> Unit) {
    // The controls take their colours from the theme of the context they are made with.
    val context: Context = if (Display.mono) androidx.appcompat.view.ContextThemeWrapper(host, R.style.Dialog_Mono) else host
    val dp = context.resources.displayMetrics.density
    fun pad(v: Int) = (v * dp).toInt()

    /** A label with the current value between a "−" and a "+" button; each press moves one step. */
    fun stepper(title: String, min: Int, max: Int, start: Int, label: (Int) -> String, onValue: (Int) -> Unit): View {
        var value = start
        val caption = TextView(context).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            minWidth = pad(110)
        }
        val minus = Button(context).apply { text = "−"; textSize = 20f }
        val plus = Button(context).apply { text = "+"; textSize = 20f }
        fun refresh() {
            caption.text = label(value)
            minus.isEnabled = value > min
            plus.isEnabled = value < max
        }
        fun step(delta: Int) {
            value = (value + delta).coerceIn(min, max)
            refresh()
            onValue(value)
        }
        minus.setOnClickListener { step(-1) }
        plus.setOnClickListener { step(1) }
        refresh()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad(8), 0, pad(8))
            addView(TextView(context).apply { text = title; textSize = 15f })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(minus, LinearLayout.LayoutParams(pad(64), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(caption)
                addView(plus, LinearLayout.LayoutParams(pad(64), LinearLayout.LayoutParams.WRAP_CONTENT))
            })
        }
    }

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(24), pad(8), pad(24), 0)
        // Font size 50%..250% in 10% steps; margins 0..4x in 0.25 steps (value stored as quarters).
        addView(stepper("Font size", 5, 25, (settings.fontSize * 10).toInt().coerceIn(5, 25), { "${it * 10}%" }) {
            settings.fontSize = it / 10.0; onChange()
        })
        addView(stepper("Margins", 0, 16, (settings.margins * 4).toInt().coerceIn(0, 16), { if (it == 4) "default" else "${it * 25}%" }) {
            settings.margins = it / 4.0; onChange()
        })
        addView(android.widget.CheckBox(context).apply {
            text = "Justify text"
            isChecked = settings.justify
            setPadding(pad(4), pad(8), 0, pad(4))
            setOnCheckedChangeListener { _, checked -> settings.justify = checked; onChange() }
        })
        addView(TextView(context).apply {
            val pace = settings.learnedPace
            text = if (pace > 0) "Reading speed: about ${pace.toInt()} words/min (learned from your reading)"
            else "Reading speed: ${DEFAULT_WORDS_PER_MINUTE.toInt()} words/min (until it learns yours)"
            textSize = 13f
            setPadding(pad(4), pad(4), 0, pad(4))
        })
    }
    Display.dialogBuilder(host)
        .setTitle("Reading settings")
        .setView(content)
        .setPositiveButton("Done", null)
        .setNeutralButton("Reset") { _, _ ->
            settings.fontSize = 1.0; settings.margins = 1.0; settings.justify = false; settings.learnedPace = 0.0; onChange()
        }
        .show().let { Display.frame(it) }
}
