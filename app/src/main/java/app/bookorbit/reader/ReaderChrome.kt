package app.bookorbit.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.BatteryManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Date

/** A chapter and where it begins in the book, as a fraction 0..1. */
class Chapter(val title: String, val start: Double)

/** A small battery: an outline with a nub, filled to the charge level, with a bolt while charging. */
class BatteryIconView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bolt = android.graphics.Path()
    var level = 100
        set(v) { field = v.coerceIn(0, 100); invalidate() }
    var charging = false
        set(v) { field = v; invalidate() }
    var color = 0xFF666666.toInt()
        set(v) { field = v; invalidate() }
    /** What the bolt is cut out of; the bar behind the icon. */
    var cutoutColor = 0xFFFFFFFF.toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((22 * dp).toInt(), (12 * dp).toInt())

    override fun onDraw(canvas: Canvas) {
        val stroke = 1.2f * dp
        val nubW = 2f * dp
        val bodyRight = width - nubW
        // Low charge shows red unless the phone is charging.
        val fill = if (level <= 15 && !charging) 0xFFD32F2F.toInt() else color

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = color
        rect.set(stroke / 2, stroke / 2, bodyRight - stroke / 2, height - stroke / 2)
        canvas.drawRoundRect(rect, 2.5f * dp, 2.5f * dp, paint)

        paint.style = Paint.Style.FILL
        rect.set(width - nubW, height * 0.3f, width.toFloat(), height * 0.7f)
        canvas.drawRoundRect(rect, 1f * dp, 1f * dp, paint)

        val inset = stroke + 1f * dp
        val innerW = bodyRight - 2 * inset
        paint.color = fill
        rect.set(inset, inset, inset + innerW * level / 100f, height - inset)
        canvas.drawRoundRect(rect, 1f * dp, 1f * dp, paint)

        if (charging) {
            val cx = bodyRight / 2
            val cy = height / 2f
            val u = dp
            bolt.reset()
            bolt.moveTo(cx + 1.2f * u, cy - 4f * u)
            bolt.lineTo(cx - 2.6f * u, cy + 0.6f * u)
            bolt.lineTo(cx - 0.2f * u, cy + 0.6f * u)
            bolt.lineTo(cx - 1.2f * u, cy + 4f * u)
            bolt.lineTo(cx + 2.6f * u, cy - 0.6f * u)
            bolt.lineTo(cx + 0.2f * u, cy - 0.6f * u)
            bolt.close()
            // Filled with the bar colour and outlined in the icon colour, so it shows over both full and empty parts.
            paint.style = Paint.Style.FILL
            paint.color = cutoutColor
            canvas.drawPath(bolt, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.9f * dp
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = color
            canvas.drawPath(bolt, paint)
        }
    }
}

/** A thin progress bar with a tick at the start of every chapter and a dot for the current position. */
class ProgressMarksView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var marks: List<Float> = emptyList()
        set(v) { field = v; invalidate() }
    var trackColor = 0x33000000
    var fillColor = 0xFF6750A4.toInt()
    var markColor = 0x99000000.toInt()

    override fun onDraw(canvas: Canvas) {
        val left = 8 * dp
        val right = width - 8 * dp
        val cy = height / 2f
        val track = 3 * dp
        val span = right - left
        paint.style = Paint.Style.FILL

        paint.color = trackColor
        rect.set(left, cy - track / 2, right, cy + track / 2)
        canvas.drawRoundRect(rect, track / 2, track / 2, paint)

        paint.color = fillColor
        rect.set(left, cy - track / 2, left + span * progress, cy + track / 2)
        canvas.drawRoundRect(rect, track / 2, track / 2, paint)

        // Chapter starts; the first one is the left end of the bar, so it needs no tick.
        paint.color = markColor
        for (m in marks) {
            if (m <= 0.002f || m >= 0.998f) continue
            val x = left + span * m
            rect.set(x - 0.75f * dp, cy - 5 * dp, x + 0.75f * dp, cy + 5 * dp)
            canvas.drawRect(rect, paint)
        }

        paint.color = fillColor
        canvas.drawCircle(left + span * progress, cy, 5 * dp, paint)
    }
}

/**
 * The two always-visible bars around the page: chapter, clock and battery above; a progress bar
 * marked with chapters below. They take the colours of the reading theme.
 */
class ReaderChrome(private val activity: AppCompatActivity) {
    private val dp = activity.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val chapterText = TextView(activity).apply {
        textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    private val clockText = TextView(activity).apply { textSize = 12f; gravity = Gravity.CENTER }
    private val batteryText = TextView(activity).apply { textSize = 12f; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val batteryIcon = BatteryIconView(activity)

    /** Battery symbol followed by the percentage, pushed to the right edge. */
    private val batteryBox = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(batteryIcon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(5) })
        addView(batteryText)
    }
    private val progressView = ProgressMarksView(activity)

    /** Chapter title on the left, clock in the middle, battery on the right. Equal weights keep the clock centred. */
    val top = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(16), px(6), px(16), px(6))
        addView(chapterText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(clockText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(batteryBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private val minutesText = TextView(activity).apply {
        textSize = 12f; gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 1
    }

    /** The progress bar with the time left in the chapter beside it. */
    val bottom: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(progressView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(minutesText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginEnd = px(16); marginStart = px(4)
        })
    }

    fun setMinutesLeft(text: String) { minutesText.text = text }

    private var chapters: List<Chapter> = emptyList()
    private var textColor = 0xFF555555.toInt()

    var backgroundColor = 0xFFFFFFFF.toInt()
        private set

    fun setChapters(list: List<Chapter>) {
        chapters = list.sortedBy { it.start }
        progressView.marks = chapters.map { it.start.toFloat() }
    }

    /** Called whenever the reader moves; [progression] is the fraction of the whole book read. */
    fun update(progression: Double?, fallbackTitle: String?) {
        val p = progression ?: 0.0
        progressView.progress = p.toFloat()
        val title = chapters.lastOrNull { it.start <= p + 0.0005 }?.title ?: fallbackTitle
        chapterText.text = title.orEmpty()
    }

    fun applyTheme(theme: String) {
        val (bg, text, fill) = when (theme) {
            "dark" -> Triple(0xFF000000.toInt(), 0xFF9E9E9E.toInt(), 0xFFD0BCFF.toInt())
            "sepia" -> Triple(0xFFFAF4E8.toInt(), 0xFF7A6A53.toInt(), 0xFF8A5A2B.toInt())
            else -> Triple(0xFFFFFFFF.toInt(), 0xFF666666.toInt(), 0xFF6750A4.toInt())
        }
        backgroundColor = bg
        textColor = text
        top.setBackgroundColor(bg)
        bottom.setBackgroundColor(bg)
        listOf(chapterText, clockText, batteryText, minutesText).forEach { it.setTextColor(text) }
        batteryIcon.color = text
        batteryIcon.cutoutColor = bg
        progressView.fillColor = fill
        progressView.trackColor = (text and 0x00FFFFFF) or 0x40000000
        progressView.markColor = (text and 0x00FFFFFF) or 0xAA000000.toInt()
        progressView.invalidate()
    }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateBattery(intent)
    }

    private fun updateClock() {
        clockText.text = android.text.format.DateFormat.getTimeFormat(activity).format(Date())
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percent = level * 100 / scale
        batteryIcon.level = percent
        batteryIcon.charging = charging
        batteryText.text = "$percent%"
    }

    fun start() {
        updateClock()
        ContextCompat.registerReceiver(
            activity, clockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The battery broadcast is sticky, so registering also returns the current state straight away.
        ContextCompat.registerReceiver(
            activity, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { updateBattery(it) }
    }

    fun stop() {
        runCatching { activity.unregisterReceiver(clockReceiver) }
        runCatching { activity.unregisterReceiver(batteryReceiver) }
    }
}
