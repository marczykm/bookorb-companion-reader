package app.companion.reader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

enum class Outcome { OK, RETRY, DROP }

class Sent(val outcome: Outcome, val createdId: Long? = null)

/** One change waiting to reach the server. [key] lets a newer change replace an older one. */
@Serializable
data class Op(val id: Long, val type: String, val key: String? = null, val data: JsonObject)

@Serializable
private class OutboxState(val ops: List<Op> = emptyList(), val nextId: Long = 1, val idMap: Map<Long, Long> = emptyMap())

/**
 * Everything the app changes while reading (progress, reading time, highlights, status, rating) goes
 * here first and is sent to the server whenever it is reachable, so none of it needs a connection.
 * The queue is kept on disk and survives the app being closed.
 */
class Outbox(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val flushLock = Mutex()
    private var ops = mutableListOf<Op>()
    private var nextId = 1L

    /** Highlights created offline get a negative temporary id; this maps it to the server's once known. */
    private var idMap = mutableMapOf<Long, Long>()

    init {
        runCatching {
            val state = json.decodeFromString<OutboxState>(file.readText())
            ops = state.ops.toMutableList(); nextId = state.nextId; idMap = state.idMap.toMutableMap()
        }
    }

    private fun persist() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(OutboxState.serializer(), OutboxState(ops, nextId, idMap)))
            tmp.renameTo(file)
        }
    }

    fun pendingOps(): List<Op> = synchronized(lock) { ops.toList() }
    fun isEmpty() = synchronized(lock) { ops.isEmpty() }

    private fun add(type: String, key: String?, build: kotlinx.serialization.json.JsonObjectBuilder.(Long) -> Unit): Long = synchronized(lock) {
        if (key != null) ops.removeAll { it.key == key }
        val id = nextId++
        ops.add(Op(id, type, key, buildJsonObject { build(id) }))
        persist()
        id
    }

    fun progress(fileId: Long, percentage: Double) {
        add("progress", "progress:$fileId") { put("fileId", fileId); put("percentage", percentage) }
    }

    fun session(fileId: Long, session: String, sessionId: String) {
        add("session", "session:$sessionId") { put("fileId", fileId); put("session", session) }
    }

    fun status(bookId: Long, status: String) = add("status", "status:$bookId") { put("bookId", bookId); put("status", status) }

    fun rating(bookId: Long, rating: Int?) {
        add("rating", "rating:$bookId") { put("bookId", bookId); if (rating != null) put("rating", rating) }
    }

    /** Returns the temporary id the highlight is known by until the server assigns one. */
    fun createAnnotation(bookId: Long, fileId: Long, cfi: String, text: String, color: String): Long = synchronized(lock) {
        val id = nextId // the op takes this id, so the temporary annotation id is its negation
        add("ann_create", null) { put("bookId", bookId); put("fileId", fileId); put("cfi", cfi); put("text", text); put("color", color) }
        -id
    }

    private fun realId(annId: Long): Long = if (annId < 0) idMap[annId] ?: annId else annId

    fun recolorAnnotation(bookId: Long, annId: Long, color: String) = synchronized(lock) {
        val id = realId(annId)
        if (id < 0) {
            // Not on the server yet: change the colour it will be created with.
            val i = ops.indexOfFirst { it.id == -id && it.type == "ann_create" }
            if (i >= 0) ops[i] = ops[i].copy(data = JsonObject(ops[i].data + ("color" to JsonPrimitive(color)))).also { persist() }
        } else {
            add("ann_color", "ann_color:$bookId:$id") { put("bookId", bookId); put("annId", id); put("color", color) }
        }
        Unit
    }

    fun deleteAnnotation(bookId: Long, annId: Long) = synchronized(lock) {
        val id = realId(annId)
        if (id < 0) {
            ops.removeAll { it.id == -id && it.type == "ann_create" }
            persist()
        } else {
            ops.removeAll { it.key == "ann_color:$bookId:$id" }
            add("ann_delete", "ann_delete:$bookId:$id") { put("bookId", bookId); put("annId", id) }
        }
        Unit
    }

    /** Sends queued changes oldest first, stopping at the first one that can't be delivered. True when the queue is empty. */
    suspend fun flush(api: ServerApi): Boolean = flushLock.withLock {
        while (true) {
            val op = synchronized(lock) { ops.firstOrNull() } ?: return@withLock true
            val sent = api.send(op)
            when (sent.outcome) {
                Outcome.RETRY -> return@withLock false
                Outcome.DROP -> remove(op.id)
                Outcome.OK -> {
                    if (op.type == "ann_create" && sent.createdId != null) synchronized(lock) { idMap[-op.id] = sent.createdId }
                    remove(op.id)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") true
    }

    private fun remove(id: Long) = synchronized(lock) {
        ops.removeAll { it.id == id }
        persist()
    }

    /** Server id for a highlight created here, once it has been delivered. */
    fun serverIdOf(tempId: Long): Long? = synchronized(lock) { idMap[tempId] }
}

/** Retries the queue when the network comes back, even if the app was closed in the meantime. */
class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        return if (app.outbox.flush(app.api)) Result.success() else Result.retry()
    }
}

/** Tries to send the queue now, and schedules a retry for when a connection is available if that fails. */
fun App.syncNow() {
    appScope.launch {
        if (!outbox.flush(api)) {
            val request = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(this@syncNow).enqueueUniqueWork("outbox-flush", ExistingWorkPolicy.KEEP, request)
        }
    }
}
