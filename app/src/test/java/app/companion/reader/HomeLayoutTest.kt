package app.companion.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeLayoutTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun layout() = HomeLayout(context)

    @Test fun startsInTheDefaultOrderWithEverythingVisible() {
        val l = layout()
        assertEquals(HOME_ITEMS.map { it.first }, l.order)
        assertEquals(l.order, l.visible)
    }

    @Test fun onlyTheHeroAndItsShelfStartMedium() {
        val l = layout()
        assertEquals("m", l.sizeOf("hero"))
        assertEquals("m", l.sizeOf("shelf:continue"))
        assertEquals("s", l.sizeOf("clock"))
    }

    @Test fun cyclingTheSizeGoesSmallMediumLarge() {
        val l = layout()
        l.cycleSize("clock"); assertEquals("m", l.sizeOf("clock"))
        l.cycleSize("clock"); assertEquals("l", l.sizeOf("clock"))
        l.cycleSize("clock"); assertEquals("s", l.sizeOf("clock"))
    }

    @Test fun movingAnItemChangesTheOrderAndStopsAtTheEnds() {
        val l = layout()
        l.move("clock", 1)
        assertEquals("pet", l.order[1]); assertEquals("clock", l.order[2])
        l.move("hero", -1)
        assertEquals("hero", l.order.first())
        l.move(l.order.last(), 5)
        assertEquals(HOME_ITEMS.size, l.order.size)
    }

    @Test fun hiddenItemsLeaveTheVisibleList() {
        val l = layout()
        l.setVisible("clock", false)
        assertFalse("clock" in l.visible)
        l.setVisible("clock", true)
        assertTrue("clock" in l.visible)
    }

    @Test fun smallNeighboursShareARow() {
        val l = layout()
        val clockRow = l.rows.first { "clock" in it }
        assertEquals(listOf("clock", "pet"), clockRow)
    }

    @Test fun mediumOrLargeCardsAreAlone() {
        val l = layout()
        l.cycleSize("clock")
        assertEquals(listOf("clock"), l.rows.first { "clock" in it })
    }

    @Test fun theHeroNeverSharesARow() {
        val l = layout()
        assertEquals(listOf("hero"), l.rows.first())
        l.cycleSize("hero"); l.cycleSize("hero")
        assertEquals(listOf("hero"), l.rows.first())
    }

    @Test fun hidingACardLetsItsNeighboursPairUp() {
        val l = layout()
        l.setVisible("pet", false)
        assertEquals(listOf("clock", "reading-streak"), l.rows.first { "clock" in it })
    }

    @Test fun layoutIsRememberedAcrossRestarts() {
        val l = layout()
        l.move("clock", 3); l.setVisible("pet", false); l.cycleSize("reading-goal")
        val again = layout()
        assertEquals(l.order, again.order)
        assertEquals(setOf("pet"), again.hidden)
        assertEquals("m", again.sizeOf("reading-goal"))
    }

    @Test fun resetBringsBackTheDefaults() {
        val l = layout()
        l.move("clock", 3); l.setVisible("pet", false); l.cycleSize("clock")
        l.reset()
        assertEquals(HOME_ITEMS.map { it.first }, l.order)
        assertTrue(l.hidden.isEmpty())
        assertEquals("s", l.sizeOf("clock"))
    }
}
