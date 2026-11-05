package app.companion.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.File
import java.io.IOException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
)

@Serializable
data class BookFileRef(val id: Long, val format: String? = null, val role: String = "")

@Serializable
data class StatusInfo(val status: String)

@Serializable
data class Library(val id: Long, val name: String)

@Serializable
private data class FilterRule(val field: String, val operator: String, val value: List<String>, val type: String = "rule")

@Serializable
private data class FilterGroup(val rules: List<FilterRule>, val join: String = "AND", val type: String = "group")

@Serializable
private data class StatusBody(val status: String)

@Serializable
private data class RatingBody(val bookIds: List<Long>, val rating: Int?)

@Serializable
data class BookCard(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    val files: List<BookFileRef> = emptyList(),
    val readingProgress: Double? = null,
    val hasCover: Boolean = false,
    val rating: Double? = null,
    val readStatus: StatusInfo? = null,
) {
    /** Formats the Readium-based reader can open. */
    fun epubFile(): BookFileRef? = files.firstOrNull { it.format.equals("epub", true) || it.format.equals("kepub", true) }
}

@Serializable
data class BooksPage(val items: List<BookCard>, val total: Int, val page: Int, val size: Int)

@Serializable
private data class SortSpec(val field: String, val dir: String)

@Serializable
private data class Pagination(val page: Int, val size: Int)

@Serializable
private data class BookQuery(val q: String? = null, val filter: FilterGroup? = null, val sort: List<SortSpec>, val pagination: Pagination)

@Serializable
private data class LoginBody(val username: String, val password: String, val clientKind: String = "native", val deviceLabel: String = "Android")

@Serializable
private data class RefreshBody(val refreshToken: String)

@Serializable
data class FileProgress(val percentage: Double = 0.0)

@Serializable
private data class SaveProgress(val percentage: Double)

@Serializable
private data class ScrollerItem(val id: String, val type: String, val limit: Int)

@Serializable
private data class ScrollerBatch(val items: List<ScrollerItem>)

@Serializable
data class ScrollerResult(val id: String, val books: List<BookCard> = emptyList(), val failed: Boolean = false)

@Serializable
private data class ScrollerBatchResponse(val items: List<ScrollerResult>)

@Serializable
data class ReadingSession(
    val sessionId: String,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Int,
    val progressDelta: Double? = null,
    val endProgress: Double? = null,
    val sessionType: String = "read",
    val source: String = "android",
)

@Serializable
data class SeriesItem(
    val id: Long, val name: String, val bookCount: Int = 0, val readCount: Int = 0,
    val authors: List<String> = emptyList(), val coverBookIds: List<Long> = emptyList(),
)

@Serializable
data class SeriesPage(val items: List<SeriesItem>, val total: Int)

@Serializable
data class AuthorItem(val id: Long, val name: String, val bookCount: Int = 0, val coverBookId: Long? = null)

@Serializable
data class AuthorsPage(val items: List<AuthorItem>, val total: Int)

@Serializable
data class Annotation(
    val id: Long,
    val cfi: String? = null,
    val text: String = "",
    val color: String = "yellow",
    val style: String = "highlight",
    val note: String? = null,
)

@Serializable
private data class NewAnnotation(val cfi: String, val bookFileId: Long, val text: String, val color: String, val style: String = "highlight")

@Serializable
private data class AnnotationPatch(val color: String)

@Serializable
data class SmartScopeItem(val id: Long, val name: String, val bookCount: Int? = null)

class AuthException(message: String) : IOException(message)

/** Persisted server URL and tokens. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v).apply()
    var accessToken: String?
        get() = prefs.getString("access", null)
        set(v) = prefs.edit().putString("access", v).apply()
    var refreshToken: String?
        get() = prefs.getString("refresh", null)
        set(v) = prefs.edit().putString("refresh", v).apply()

    val isLoggedIn get() = serverUrl.isNotEmpty() && refreshToken != null

    fun clearTokens() {
        prefs.edit().remove("access").remove("refresh").apply()
    }
}

class ServerApi(private val session: Session, val cacheDir: File, private val filesDir: File) {
    lateinit var outbox: Outbox

    /** False while the last read had to fall back to saved data because the server couldn't be reached. */
    val online = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** True while a request to the server is in flight (and for a moment after, so quick ones can still be seen). */
    val syncing = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearSyncing = Runnable { if (inFlight.get() == 0) syncing.value = false }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val jsonType = "application/json".toMediaType()
    private val plain = OkHttpClient()

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val token = session.accessToken
            val req = if (token != null && chain.request().header("Authorization") == null) {
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            } else chain.request()
            // Cover images are not syncing; everything else that talks to the server is.
            val path = req.url.encodedPath
            val counted = !path.endsWith("/thumbnail") && !path.endsWith("/cover")
            if (counted) { inFlight.incrementAndGet(); mainHandler.removeCallbacks(clearSyncing); syncing.value = true }
            try {
                chain.proceed(req)
            } finally {
                if (counted && inFlight.decrementAndGet() == 0) mainHandler.postDelayed(clearSyncing, 700)
            }
        }
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (response.request.url.encodedPath.endsWith("/auth/refresh")) return null
                if (response.priorResponse != null) return null
                synchronized(this) {
                    val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
                    // Another request may already have refreshed while we waited for the lock.
                    if (session.accessToken != null && session.accessToken != sent) {
                        return response.request.newBuilder().header("Authorization", "Bearer ${session.accessToken}").build()
                    }
                    if (!refresh()) return null
                    return response.request.newBuilder().header("Authorization", "Bearer ${session.accessToken}").build()
                }
            }
        })
        .build()

    private fun url(path: String) = session.serverUrl.trimEnd('/') + "/api/v1" + path

    fun thumbnailUrl(bookId: Long) = url("/books/$bookId/thumbnail")

    private val dataDir by lazy { File(filesDir, "data").apply { mkdirs() } }

    private fun cacheFile(key: String) =
        File(dataDir, java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    /**
     * Fetches [key] from the server and keeps a copy; when the server can't be reached, answers from
     * that copy so the app keeps working offline. Only fails when nothing has been saved yet.
     */
    private fun cached(key: String, fetch: () -> String): String {
        val file = cacheFile(key)
        return try {
            val text = fetch()
            runCatching { file.writeText(text) }
            online.value = true
            text
        } catch (e: IOException) {
            if (e is AuthException) throw e
            if (!file.exists()) { online.value = false; throw e }
            online.value = false
            file.readText()
        }
    }

    private fun Request.fetchText(): String = client.newCall(this).execute().use { r ->
        if (r.code == 401) throw AuthException("Session expired")
        if (!r.isSuccessful) throw IOException("Server returned HTTP ${r.code}")
        r.body!!.string()
    }

    /** Applies status and rating changes that haven't reached the server yet, so lists agree with what the user did. */
    private fun withPending(b: BookCard): BookCard {
        var out = b
        for (op in outbox.pendingOps()) {
            val d = op.data
            if (d["bookId"]?.jsonPrimitive?.longOrNull != b.id) continue
            when (op.type) {
                "status" -> out = out.copy(readStatus = StatusInfo(d["status"]!!.jsonPrimitive.content))
                "rating" -> out = out.copy(rating = d["rating"]?.jsonPrimitive?.doubleOrNull)
            }
        }
        return out
    }

    private fun BooksPage.withPending() = copy(items = items.map { withPending(it) })

    private fun refresh(): Boolean {
        val rt = session.refreshToken ?: return false
        val req = Request.Builder().url(url("/auth/refresh"))
            .post(json.encodeToString(RefreshBody(rt)).toRequestBody(jsonType)).build()
        return try {
            plain.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    if (r.code == 401) { session.clearTokens(); return false }
                    throw IOException("Server returned HTTP ${r.code}") // transient: keep the session
                }
                val auth = json.decodeFromString<AuthResponse>(r.body!!.string())
                session.accessToken = auth.accessToken
                session.refreshToken = auth.refreshToken
                true
            }
        } catch (e: IOException) {
            throw e // offline or server down: never log the user out for that
        }
    }

    /** A server typed without a scheme is tried over https first, then plain http (typical for a home server). */
    suspend fun login(server: String, username: String, password: String) = withContext(Dispatchers.IO) {
        val raw = server.trim().trimEnd('/')
        val candidates = if (raw.startsWith("http://") || raw.startsWith("https://")) listOf(raw) else listOf("https://$raw", "http://$raw")
        var failure: IOException? = null
        for (base in candidates) {
            try {
                base.toHttpUrl() // validates
                loginAt(base, username, password)
                return@withContext
            } catch (e: AuthException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid server address")
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("Could not connect")
    }

    private fun loginAt(base: String, username: String, password: String) {
        val previous = session.serverUrl
        session.serverUrl = base
        try {
            val req = Request.Builder().url(url("/auth/login"))
                .post(json.encodeToString(LoginBody(username, password)).toRequestBody(jsonType)).build()
            plain.newCall(req).execute().use { r ->
                if (r.code == 401) throw AuthException("Wrong username or password")
                if (!r.isSuccessful) throw IOException("Server returned HTTP ${r.code}")
                val auth = json.decodeFromString<AuthResponse>(r.body!!.string())
                session.accessToken = auth.accessToken
                session.refreshToken = auth.refreshToken
            }
        } catch (e: Exception) {
            session.serverUrl = previous
            throw e
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        session.refreshToken?.let { rt ->
            runCatching {
                plain.newCall(Request.Builder().url(url("/auth/logout"))
                    .post(json.encodeToString(RefreshBody(rt)).toRequestBody(jsonType)).build()).execute().close()
            }
        }
        session.clearTokens()
    }

    suspend fun books(query: String?, page: Int, size: Int = 40, libraryId: Long? = null, status: String? = null): BooksPage = withContext(Dispatchers.IO) {
        val body = BookQuery(
            q = query?.takeIf { it.isNotBlank() },
            filter = status?.let { FilterGroup(listOf(FilterRule("readStatus", "includesAny", listOf(it)))) },
            sort = listOf(SortSpec(if (query.isNullOrBlank()) "addedAt" else "relevance", if (query.isNullOrBlank()) "desc" else "desc")),
            pagination = Pagination(page, size),
        )
        val req = Request.Builder().url(url(if (libraryId != null) "/libraries/$libraryId/books" else "/books/query")).post(json.encodeToString(body).toRequestBody(jsonType)).build()
        json.decodeFromString<BooksPage>(cached("books:$libraryId:${json.encodeToString(body)}") { req.fetchText() }).withPending()
    }

    /** Downloads the book file into the app cache (once) and returns it. */
    suspend fun downloadFile(fileId: Long, ext: String): File = withContext(Dispatchers.IO) {
        val dir = File(filesDir, "books").apply { mkdirs() }
        val target = File(dir, "$fileId.$ext")
        if (target.exists() && target.length() > 0) return@withContext target
        val tmp = File(dir, "$fileId.$ext.part")
        client.newCall(Request.Builder().url(url("/books/files/$fileId/serve")).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed: HTTP ${r.code}")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        }
        tmp.renameTo(target)
        target
    }

    suspend fun getProgress(fileId: Long): Double = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url("/books/files/$fileId/progress")).build()).execute().use { r ->
                if (!r.isSuccessful) 0.0 else json.decodeFromString<FileProgress>(r.body!!.string()).percentage
            }
        }.getOrDefault(0.0)
    }

    /** Home-screen shelves, keyed by the id given in [shelves] (id to scroller type). */
    suspend fun shelves(shelves: List<Pair<String, String>>, limit: Int = 20): Map<String, List<BookCard>> = withContext(Dispatchers.IO) {
        val body = ScrollerBatch(shelves.map { ScrollerItem(it.first, it.second, limit) })
        val req = Request.Builder().url(url("/dashboard/scrollers/batch")).post(json.encodeToString(body).toRequestBody(jsonType)).build()
        json.decodeFromString<ScrollerBatchResponse>(cached("shelves") { req.fetchText() }).items
            .associate { it.id to it.books.map { b -> withPending(b) } }
    }

    suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        runCatching {
            json.decodeFromString<List<Library>>(cached("libraries") { Request.Builder().url(url("/libraries")).build().fetchText() })
        }.getOrDefault(emptyList())
    }

    fun isOnDevice(fileId: Long) = File(filesDir, "books/$fileId.epub").exists()

    private fun <T> get(path: String, query: Map<String, String>, parse: (String) -> T): T {
        val builder = url(path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val request = Request.Builder().url(builder.build()).build()
        return parse(cached("get:${request.url.encodedPath}?${request.url.encodedQuery}") { request.fetchText() })
    }

    private fun q(query: String?, page: Int, size: Int = 50) =
        buildMap { put("page", page.toString()); put("size", size.toString()); query?.takeIf { it.isNotBlank() }?.let { put("q", it) } }

    suspend fun series(query: String?, page: Int): SeriesPage = withContext(Dispatchers.IO) {
        get("/series", q(query, page)) { json.decodeFromString<SeriesPage>(it) }
    }

    suspend fun seriesBooks(id: Long, page: Int): BooksPage = withContext(Dispatchers.IO) {
        get("/series/$id/books", q(null, page)) { json.decodeFromString<BooksPage>(it).withPending() }
    }

    /** The user's SmartScopes (saved filters). The server returns them all at once, in the user's order. */
    suspend fun smartScopes(): List<SmartScopeItem> = withContext(Dispatchers.IO) {
        get("/smart-scopes", emptyMap()) { json.decodeFromString<List<SmartScopeItem>>(it) }
    }

    suspend fun smartScopeBooks(id: Long, page: Int): BooksPage = withContext(Dispatchers.IO) {
        get("/smart-scopes/$id/books", q(null, page)) { json.decodeFromString<BooksPage>(it).withPending() }
    }

    suspend fun authors(query: String?, page: Int): AuthorsPage = withContext(Dispatchers.IO) {
        get("/authors", q(query, page)) { json.decodeFromString<AuthorsPage>(it) }
    }

    suspend fun authorBooks(id: Long, page: Int): BooksPage = withContext(Dispatchers.IO) {
        get("/authors/$id/books", q(null, page)) { json.decodeFromString<BooksPage>(it).withPending() }
    }

    // ---- Reading history ----

    /** A page of finished reads. [year] null means all time. */
    suspend fun history(year: Int?, search: String?, offset: Int, limit: Int = 50): HistoryPage = withContext(Dispatchers.IO) {
        val query = buildMap {
            put("outcomes", "completed,skimmed,abandoned"); put("sort", "newest"); put("offset", offset.toString()); put("limit", limit.toString())
            year?.let { put("year", it.toString()) }
            search?.trim()?.takeIf { it.isNotEmpty() }?.let { put("search", it) }
        }
        get("/reading-history", query) { json.decodeFromString<HistoryPage>(it) }
    }

    suspend fun historySummary(year: Int?): HistorySummary = withContext(Dispatchers.IO) {
        get("/reading-history/summary", year?.let { mapOf("year" to it.toString()) } ?: emptyMap()) { json.decodeFromString<HistorySummary>(it) }
    }

    // ---- Activity ----

    suspend fun activityOverview(): ActivityOverview = withContext(Dispatchers.IO) {
        get("/user-statistics/activity-overview", emptyMap()) { json.decodeFromString<ActivityOverview>(it) }
    }

    suspend fun activityCalendar(year: Int): ActivityCalendar = withContext(Dispatchers.IO) {
        get("/user-statistics/activity-calendar/$year", emptyMap()) { json.decodeFromString<ActivityCalendar>(it) }
    }

    suspend fun achievements(): AchievementCatalogue = withContext(Dispatchers.IO) {
        get("/achievements", emptyMap()) { json.decodeFromString<AchievementCatalogue>(it) }
    }

    // ---- Highlights hub ----

    suspend fun hubHighlights(page: Int, search: String?, colors: List<String>, hasNote: Boolean, pageSize: Int = 50): HubPage = withContext(Dispatchers.IO) {
        val query = buildMap {
            put("page", page.toString()); put("pageSize", pageSize.toString()); put("sortBy", "createdAt"); put("sortDir", "desc")
            search?.trim()?.takeIf { it.isNotEmpty() }?.let { put("search", it) }
            if (colors.isNotEmpty()) put("colors", colors.joinToString(","))
            if (hasNote) put("hasNote", "true")
        }
        get("/annotations", query) { json.decodeFromString<HubPage>(it) }
    }

    suspend fun hubOverview(): HubOverview = withContext(Dispatchers.IO) {
        get("/annotations/overview", emptyMap()) { json.decodeFromString<HubOverview>(it) }
    }

    // ---- Book page ----

    suspend fun bookDetail(id: Long): BookDetail = withContext(Dispatchers.IO) {
        get("/books/$id", emptyMap()) { json.decodeFromString<BookDetail>(it) }
    }

    private suspend fun related(bookId: Long, kind: String): List<RelatedBook> = withContext(Dispatchers.IO) {
        runCatching { get("/books/$bookId/$kind", emptyMap()) { json.decodeFromString<List<RelatedBook>>(it) } }.getOrDefault(emptyList())
    }
    suspend fun recommendations(bookId: Long) = related(bookId, "recommendations")
    suspend fun seriesBooks(bookId: Long) = related(bookId, "series-books")
    suspend fun authorBooks(bookId: Long) = related(bookId, "author-books")

    suspend fun journal(bookId: Long): List<JournalEntry> = withContext(Dispatchers.IO) {
        runCatching { get("/books/$bookId/journal", emptyMap()) { json.decodeFromString<List<JournalEntry>>(it) } }.getOrDefault(emptyList())
    }

    /** Every reading session of a book, newest first (the server pages by 100). */
    suspend fun sessions(bookId: Long): SessionsPage = withContext(Dispatchers.IO) {
        fun query(page: Int) = mapOf("page" to page.toString(), "pageSize" to "100", "sortBy" to "startedAt", "sortDir" to "desc")
        var first = get("/books/$bookId/sessions", query(1)) { json.decodeFromString<SessionsPage>(it) }
        var page = 1
        while (first.items.size < first.total && page < 20) {
            page++
            val more = get("/books/$bookId/sessions", query(page)) { json.decodeFromString<SessionsPage>(it) }
            if (more.items.isEmpty()) break
            first = first.copy(items = first.items + more.items)
        }
        first
    }

    suspend fun addJournalEntry(bookId: Long, body: String, positionPercent: Double?) = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("clientId", JsonPrimitive(java.util.UUID.randomUUID().toString()))
            put("body", JsonPrimitive(body))
            positionPercent?.let { put("positionPercent", JsonPrimitive(it)) }
        }
        Request.Builder().url(url("/books/$bookId/journal")).post(payload.toString().toRequestBody(jsonType)).build().fetchText()
        Unit
    }

    suspend fun setPersonalNote(bookId: Long, note: String?) = withContext(Dispatchers.IO) {
        val payload = kotlinx.serialization.json.buildJsonObject { put("note", note?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull) }
        Request.Builder().url(url("/books/$bookId/personal-note")).patch(payload.toString().toRequestBody(jsonType)).build().fetchText()
        Unit
    }

    suspend fun annotations(bookId: Long, fileId: Long): List<Annotation>? = withContext(Dispatchers.IO) {
        runCatching {
            get("/books/$bookId/annotations", mapOf("readerFileId" to fileId.toString())) { json.decodeFromString<List<Annotation>>(it) }
        }.getOrNull()
    }

    /** Dashboard widget payloads by widget type; widgets that failed or have no data are left out. */
    suspend fun widgets(types: List<String>): Map<String, kotlinx.serialization.json.JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("widgets", kotlinx.serialization.json.JsonArray(types.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }.toString().toRequestBody(jsonType)
            val request = Request.Builder().url(url("/dashboard/widgets/batch")).post(body).build()
            val items = Json.parseToJsonElement(cached("widgets") { request.fetchText() }).let { it as kotlinx.serialization.json.JsonObject }["items"]
                as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyMap()
            buildMap {
                for (item in items) {
                    val o = item as? kotlinx.serialization.json.JsonObject ?: continue
                    val type = (o["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                    (o["data"] as? kotlinx.serialization.json.JsonObject)?.let { put(type, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Delivers one queued change. Network trouble means "try again later"; a refusal means "give up on it". */
    suspend fun send(op: Op): Sent = withContext(Dispatchers.IO) {
        val d = op.data
        fun long(k: String) = d[k]!!.jsonPrimitive.content.toLong()
        fun str(k: String) = d[k]!!.jsonPrimitive.content
        val request = when (op.type) {
            "progress" -> Request.Builder().url(url("/books/files/${long("fileId")}/progress"))
                .post(json.encodeToString(SaveProgress(d["percentage"]!!.jsonPrimitive.content.toDouble().coerceIn(0.0, 100.0))).toRequestBody(jsonType))
            "session" -> {
                val session = Json.parseToJsonElement(str("session")) as kotlinx.serialization.json.JsonObject
                val complete = kotlinx.serialization.json.JsonObject(
                    mapOf("sessionType" to kotlinx.serialization.json.JsonPrimitive("read"), "source" to kotlinx.serialization.json.JsonPrimitive("android")) + session,
                )
                Request.Builder().url(url("/books/files/${long("fileId")}/sessions")).post(complete.toString().toRequestBody(jsonType))
            }
            "status" -> Request.Builder().url(url("/books/${long("bookId")}/status")).patch(json.encodeToString(StatusBody(str("status"))).toRequestBody(jsonType))
            "rating" -> Request.Builder().url(url("/books/bulk-set-rating"))
                .post(json.encodeToString(RatingBody(listOf(long("bookId")), d["rating"]?.jsonPrimitive?.content?.toIntOrNull())).toRequestBody(jsonType))
            "ann_create" -> Request.Builder().url(url("/books/${long("bookId")}/annotations"))
                .post(json.encodeToString(NewAnnotation(str("cfi"), long("fileId"), str("text"), str("color"))).toRequestBody(jsonType))
            "ann_color" -> Request.Builder().url(url("/books/${long("bookId")}/annotations/${long("annId")}"))
                .patch(json.encodeToString(AnnotationPatch(str("color"))).toRequestBody(jsonType))
            "ann_delete" -> Request.Builder().url(url("/books/${long("bookId")}/annotations/${long("annId")}")).delete()
            else -> return@withContext Sent(Outcome.DROP)
        }.build()
        try {
            client.newCall(request).execute().use { r ->
                when {
                    r.isSuccessful -> Sent(Outcome.OK, if (op.type == "ann_create") runCatching { json.decodeFromString<Annotation>(r.body!!.string()).id }.getOrNull() else null)
                    r.code == 401 || r.code == 408 || r.code == 429 || r.code >= 500 -> Sent(Outcome.RETRY)
                    else -> Sent(Outcome.DROP)
                }
            }
        } catch (e: IOException) {
            Sent(Outcome.RETRY)
        }
    }
}
