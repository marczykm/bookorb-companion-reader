package app.companion.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FormatTest {
    @Test fun hmShowsSecondsMinutesAndHours() {
        assertEquals("45s", hm(45.0))
        assertEquals("10m", hm(600.0))
        assertEquals("1h 05m", hm(3900.0))
        assertEquals("2h 00m", hm(7200.0))
    }

    @Test fun shortDurationKeepsHoursOrMinutes() {
        assertEquals("3h", shortDuration(3 * 3600 + 1200))
        assertEquals("20m", shortDuration(1200))
        assertEquals("1m", shortDuration(5))
    }

    @Test fun formatSecondsCombinesHoursAndMinutes() {
        assertEquals("1h 20m", formatSeconds(4800.0))
        assertEquals("1h", formatSeconds(3600.0))
        assertEquals("25m", formatSeconds(1500.0))
        assertEquals("0m", formatSeconds(0.0))
    }

    @Test fun parseDayReadsTheDatePartOfATimestamp() {
        assertEquals(LocalDate.of(2026, 11, 5), parseDay("2026-11-05T08:00:00Z"))
        assertEquals(LocalDate.of(2026, 11, 5), parseDay("2026-11-05"))
        assertNull(parseDay("soon"))
        assertNull(parseDay(null))
    }

    @Test fun parseInstantAcceptsZonedAndPlainTimestamps() {
        assertNotNull(parseInstant("2026-11-05T08:00:00Z"))
        assertNotNull(parseInstant("2026-11-05T08:00:00.123+01:00"))
        assertNotNull(parseInstant("2026-11-05"))
        assertNull(parseInstant("nonsense"))
    }

    @Test fun plainTextStripsTagsAndEntities() {
        assertEquals("A bold book & more", plainText("<p>A <b>bold</b>&nbsp;book &amp; more</p>"))
    }

    @Test fun secondsLeftNeedsAPaceAndAProgress() {
        val pace = SessionsPage.Stats(paceProgressDelta = 4.0, paceDurationSeconds = 1200.0)
        // 70% read: 30 points to go at 4 points per 20 minutes is 9000 seconds.
        assertEquals(9000.0, secondsLeft(pace, 70.0)!!, 0.001)
        assertEquals(0.0, secondsLeft(pace, 100.0)!!, 0.001)
        assertNull(secondsLeft(pace, null))
        assertNull(secondsLeft(SessionsPage.Stats(), 70.0))
    }

    @Test fun seriesIndexMayBeANumberOrAString() {
        val json = Json { ignoreUnknownKeys = true }
        val asNumber = json.decodeFromString<RelatedBook>("""{"id":1,"seriesIndex":2}""")
        val asString = json.decodeFromString<RelatedBook>("""{"id":1,"seriesIndex":"2.5"}""")
        val missing = json.decodeFromString<RelatedBook>("""{"id":1}""")
        assertEquals("2", asNumber.seriesIndex.text())
        assertEquals("2.5", asString.seriesIndex.text())
        assertNull(missing.seriesIndex.text())
        assertEquals("x", JsonPrimitive("x").text())
    }

    @Test fun historyPageToleratesMissingFields() {
        val json = Json { ignoreUnknownKeys = true }
        val page = json.decodeFromString<HistoryPage>("""{"items":[{"id":3,"title":"T"}],"total":1}""")
        assertEquals("completed", page.items[0].outcome)
        assertEquals(0, page.items[0].highlightCount)
        assertNull(page.months)
    }
}
