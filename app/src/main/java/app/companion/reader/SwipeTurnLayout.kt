package app.companion.reader

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Turns a horizontal swipe into an instant page turn. The reader's own swipe handling slides the page
 * in; this catches the swipe first, so the page changes without any animation.
 *
 * [canSwipe] says whether a swipe may be taken (not while text is being selected, for example), and
 * [onSwipe] is told which way: true for "next page", which is a swipe to the left.
 */
class SwipeTurnLayout(
    context: Context,
    private val canSwipe: () -> Boolean,
    private val onSwipe: (forward: Boolean) -> Unit,
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minDistance = 48 * resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var swiping = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                tracking = true; swiping = false
            }
            // A second finger means pinch or selection handles, not a page swipe.
            MotionEvent.ACTION_POINTER_DOWN -> tracking = false
            MotionEvent.ACTION_MOVE -> if (tracking && !swiping && canSwipe()) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    return true // the page underneath gets a cancel and stops following the finger
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracking = false; swiping = false }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!swiping) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                swiping = false; tracking = false
                if (abs(dx) >= minDistance) onSwipe(dx < 0)
            }
            MotionEvent.ACTION_CANCEL -> { swiping = false; tracking = false }
        }
        return true
    }
}
