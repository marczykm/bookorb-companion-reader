package app.companion.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PetsTest {
    private fun pose(hasBook: Boolean = true, streak: Int = 0, longest: Int = 0, today: Int = 10, finished: Boolean = false, hour: Int = 14) =
        Pets.pose(hasBook, streak, longest, today, finished, hour)

    @Test fun waitsWhenThereIsNoBookAndNoStreak() = assertEquals("wait", pose(hasBook = false))

    @Test fun sleepsUntilYouReadToday() = assertEquals("sleep", pose(today = 0, streak = 3, longest = 3))

    @Test fun ribbonWhenABookWasFinishedToday() = assertEquals("ribbon", pose(finished = true, streak = 7))

    @Test fun partyOnMilestones() {
        for (days in listOf(7, 30, 50, 100, 200, 365)) assertEquals("party", pose(streak = days, longest = 400))
    }

    @Test fun partyWhenTheStreakIsAtItsBest() = assertEquals("party", pose(streak = 5, longest = 5))

    @Test fun aSingleDayIsNotAPartyUnlessAMilestone() = assertEquals("read", pose(streak = 1, longest = 1))

    @Test fun yawnsLateAtNightAndVeryEarly() {
        assertEquals("yawn", pose(hour = 22))
        assertEquals("yawn", pose(hour = 3))
        assertEquals("read", pose(hour = 5))
        assertEquals("read", pose(hour = 21))
    }

    @Test fun finishedBeatsParty() = assertEquals("ribbon", pose(finished = true, streak = 30, longest = 30))

    @Test fun captionsFollowThePose() {
        assertEquals("Reading with you" to "45 min today", Pets.lines("read", 0, 0, 45))
        assertEquals("Reading with you" to "1 h 5 min today", Pets.lines("read", 0, 0, 65))
        assertEquals("7 days in a row!" to "A new best", Pets.lines("party", 7, 7, 10))
        assertEquals("7 days in a row!" to "Your best is 12", Pets.lines("party", 7, 12, 10))
        assertEquals("1 day in a row!" to "A new best", Pets.lines("party", 1, 1, 10))
        assertEquals("Book finished!" to "", Pets.lines("ribbon", 0, 0, 0))
        assertEquals("Ready when you are" to "Open a book to start", Pets.lines("wait", 0, 0, 0))
    }
}
