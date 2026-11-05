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
        assertEquals("s", l.sizeOf("pet"))
    }

    @Test fun cyclingTheSizeGoesSmallMediumLarge() {
        val l = layout()
        l.cycleSize("pet"); assertEquals("m", l.sizeOf("pet"))
        l.cycleSize("pet"); assertEquals("l", l.sizeOf("pet"))
        l.cycleSize("pet"); assertEquals("s", l.sizeOf("pet"))
    }

    @Test fun movingAnItemChangesTheOrderAndStopsAtTheEnds() {
        val l = layout()
        l.move("pet", 1)
        assertEquals("reading-streak", l.order[1]); assertEquals("pet", l.order[2])
        l.move("hero", -1)
        assertEquals("hero", l.order.first())
        l.move(l.order.last(), 5)
        assertEquals(HOME_ITEMS.size, l.order.size)
    }

    @Test fun hiddenItemsLeaveTheVisibleList() {
        val l = layout()
        l.setVisible("pet", false)
        assertFalse("pet" in l.visible)
        l.setVisible("pet", true)
        assertTrue("pet" in l.visible)
    }

    @Test fun smallNeighboursShareARow() {
        val l = layout()
        val petRow = l.rows.first { "pet" in it }
        assertEquals(listOf("pet", "reading-streak"), petRow)
    }

    @Test fun mediumOrLargeCardsAreAlone() {
        val l = layout()
        l.cycleSize("pet")
        assertEquals(listOf("pet"), l.rows.first { "pet" in it })
    }

    @Test fun theHeroNeverSharesARow() {
        val l = layout()
        assertEquals(listOf("hero"), l.rows.first())
        l.cycleSize("hero"); l.cycleSize("hero")
        assertEquals(listOf("hero"), l.rows.first())
    }

    @Test fun hidingACardLetsItsNeighboursPairUp() {
        val l = layout()
        l.setVisible("reading-streak", false)
        assertEquals(listOf("pet", "reading-goal"), l.rows.first { "pet" in it })
    }

    @Test fun layoutIsRememberedAcrossRestarts() {
        val l = layout()
        l.move("pet", 3); l.setVisible("reading-streak", false); l.cycleSize("reading-goal")
        val again = layout()
        assertEquals(l.order, again.order)
        assertEquals(setOf("reading-streak"), again.hidden)
        assertEquals("m", again.sizeOf("reading-goal"))
    }

    @Test fun resetBringsBackTheDefaults() {
        val l = layout()
        l.move("pet", 3); l.setVisible("reading-streak", false); l.cycleSize("pet")
        l.reset()
        assertEquals(HOME_ITEMS.map { it.first }, l.order)
        assertTrue(l.hidden.isEmpty())
        assertEquals("s", l.sizeOf("pet"))
    }
}
