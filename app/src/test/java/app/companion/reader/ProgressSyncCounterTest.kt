package app.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProgressSyncCounterTest {
    private fun fires(counter: ProgressSyncCounter, positions: List<Double>, moved: Boolean = true) =
        positions.count { counter.onPosition(it, moved) }

    @Test fun firesOnEveryTenthPageTurn() {
        val counter = ProgressSyncCounter(10)
        // The first position only sets a baseline; then 25 turns give two uploads.
        assertEquals(2, fires(counter, (0..25).map { it / 100.0 }))
    }

    @Test fun nothingBeforeTenTurns() {
        val counter = ProgressSyncCounter(10)
        assertEquals(0, fires(counter, (0..9).map { it / 100.0 }))
    }

    @Test fun reportingTheSamePositionIsNotATurn() {
        val counter = ProgressSyncCounter(2)
        counter.onPosition(0.1, true)
        repeat(20) { assertFalse(counter.onPosition(0.1, true)) }
    }

    @Test fun nothingUntilTheReaderHasMoved() {
        val counter = ProgressSyncCounter(2)
        assertEquals(0, fires(counter, (0..30).map { it / 100.0 }, moved = false))
    }

    @Test fun turningBackCountsToo() {
        val counter = ProgressSyncCounter(3)
        val results = listOf(0.5, 0.4, 0.3, 0.2).map { counter.onPosition(it, true) }
        assertEquals(listOf(false, false, false, true), results)
    }

    @Test fun countStartsOverAfterAnUpload() {
        val counter = ProgressSyncCounter(2)
        val results = (0..6).map { counter.onPosition(it / 10.0, true) }
        assertEquals(listOf(false, false, true, false, true, false, true), results)
    }
}
