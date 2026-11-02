package app.companion.reader

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OutboxTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file: File by lazy { File(tmp.root, "outbox.json") }
    private fun outbox() = Outbox(file)
    private fun Op.str(key: String) = data[key]?.jsonPrimitive?.content

    @Test fun startsEmpty() = assertTrue(outbox().isEmpty())

    @Test fun newerProgressReplacesOlderForTheSameBook() {
        val box = outbox()
        box.progress(1, 10.0); box.progress(1, 20.0); box.progress(1, 30.0)
        val ops = box.pendingOps()
        assertEquals(1, ops.size)
        assertEquals("30.0", ops[0].str("percentage"))
    }

    @Test fun progressOfDifferentBooksIsKeptSeparately() {
        val box = outbox()
        box.progress(1, 10.0); box.progress(2, 20.0)
        assertEquals(2, box.pendingOps().size)
    }

    @Test fun sessionsAreKeyedBySessionId() {
        val box = outbox()
        box.session(1, "a", "s1"); box.session(1, "b", "s1"); box.session(1, "c", "s2")
        val ops = box.pendingOps()
        assertEquals(2, ops.size)
        assertEquals(listOf("b", "c"), ops.map { it.str("session") })
    }

    @Test fun opsKeepTheirOrder() {
        val box = outbox()
        box.status(1, "READING"); box.progress(1, 5.0); box.rating(1, 4)
        assertEquals(listOf("status", "progress", "rating"), box.pendingOps().map { it.type })
    }

    @Test fun clearingARatingLeavesOutTheValue() {
        val box = outbox()
        box.rating(1, null)
        assertNull(box.pendingOps().single().data["rating"])
    }

    @Test fun queueSurvivesRestart() {
        val box = outbox()
        box.progress(7, 42.0); box.status(7, "FINISHED")
        val reopened = outbox()
        assertEquals(listOf("progress", "status"), reopened.pendingOps().map { it.type })
        // New ops continue the id sequence instead of reusing ids.
        reopened.rating(7, 5)
        assertEquals(3, reopened.pendingOps().map { it.id }.toSet().size)
    }

    @Test fun aDamagedFileIsIgnored() {
        file.writeText("{ not json")
        assertTrue(outbox().isEmpty())
    }

    @Test fun offlineHighlightGetsANegativeTemporaryId() {
        val box = outbox()
        val temp = box.createAnnotation(1, 2, "cfi", "text", "yellow")
        assertTrue(temp < 0)
        val create = box.pendingOps().single()
        assertEquals("ann_create", create.type)
        assertEquals(-temp, create.id)
    }

    @Test fun recolouringAHighlightNotYetSentChangesTheQueuedCreate() {
        val box = outbox()
        val temp = box.createAnnotation(1, 2, "cfi", "text", "yellow")
        box.recolorAnnotation(1, temp, "green")
        val ops = box.pendingOps()
        assertEquals(1, ops.size)
        assertEquals("green", ops[0].str("color"))
    }

    @Test fun deletingAHighlightNotYetSentCancelsIt() {
        val box = outbox()
        val temp = box.createAnnotation(1, 2, "cfi", "text", "yellow")
        box.deleteAnnotation(1, temp)
        assertTrue(box.isEmpty())
    }

    @Test fun recolouringTwiceKeepsOnlyTheLastColour() {
        val box = outbox()
        box.recolorAnnotation(1, 50, "green"); box.recolorAnnotation(1, 50, "blue")
        val op = box.pendingOps().single()
        assertEquals("ann_color", op.type)
        assertEquals("blue", op.str("color"))
    }

    @Test fun deletingASentHighlightDropsItsPendingRecolour() {
        val box = outbox()
        box.recolorAnnotation(1, 50, "green")
        box.deleteAnnotation(1, 50)
        assertEquals(listOf("ann_delete"), box.pendingOps().map { it.type })
    }

    @Test fun nothingKnownAboutAHighlightUntilItIsDelivered() = assertNull(outbox().serverIdOf(-1))
}
