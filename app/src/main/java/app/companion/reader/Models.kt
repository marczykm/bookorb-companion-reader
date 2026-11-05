package app.companion.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** A number or string from the server as text; some fields (series index) arrive as either. */
fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.content?.takeIf { it != "null" }

// ---- Reading history ----

/** One finished read: a completed, skimmed or abandoned attempt. */
@Serializable
data class HistoryItem(
    val id: Long,
    val bookId: Long? = null,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    val seriesIndex: JsonElement? = null,
    val format: String? = null,
    val endedOn: String? = null,
    val outcome: String = "completed",
    val readNumber: Int? = null,
    val progressPercent: Double? = null,
    val seconds: Int = 0,
    val rating: Double? = null,
    val highlightCount: Int = 0,
    val journalCount: Int = 0,
)

/** A month of the list, in order, so section headers can be laid out before the rows load. */
@Serializable
data class HistoryMonth(val month: String, val reads: Int = 0, val seconds: Int = 0)

@Serializable
data class HistoryPage(val items: List<HistoryItem> = emptyList(), val offset: Int = 0, val total: Int = 0, val months: List<HistoryMonth>? = null)

@Serializable
data class HistorySummary(
    val years: List<YearCount> = emptyList(),
    val reads: Int = 0,
    val books: Int = 0,
    val rereads: Int = 0,
    val months: List<MonthCount> = emptyList(),
    val goal: Goal? = null,
) {
    @Serializable data class YearCount(val year: Int, val reads: Int = 0)
    @Serializable data class MonthCount(val month: Int, val reads: Int = 0)
    @Serializable data class Goal(val target: Int = 0, val reached: Int = 0, val metOn: String? = null)
}

// ---- Activity ----

@Serializable
data class ActivityDuration(val totalSeconds: Double = 0.0, val readingSeconds: Double = 0.0, val listeningSeconds: Double = 0.0)

@Serializable
data class ActivityDay(
    val day: String,
    val totalSeconds: Double = 0.0,
    val readingSeconds: Double = 0.0,
    val listeningSeconds: Double = 0.0,
    val sessionsCount: Int = 0,
)

@Serializable
data class ActivityCalendar(val year: Int, val availableYears: List<Int> = emptyList(), val days: List<ActivityDay> = emptyList())

@Serializable
data class ActivityOverview(
    val snapshot: Snapshot,
    val dailyActivity: Daily = Daily(),
    val calendar: ActivityCalendar? = null,
    val goal: Goal? = null,
    val rhythm: Rhythm = Rhythm(),
    val completion: Completion = Completion(),
    val sources: Sources = Sources(),
    val pace: Pace = Pace(),
) {
    @Serializable
    data class Snapshot(
        val today: ActivityDuration = ActivityDuration(),
        val lastSevenDays: ActivityDuration = ActivityDuration(),
        val previousSevenDays: ActivityDuration = ActivityDuration(),
        val currentStreak: Int = 0,
        val longestStreak: Int = 0,
        val completedBooksYtd: Int = 0,
    )
    @Serializable data class Daily(val days: List<ActivityDay> = emptyList())
    @Serializable
    data class Goal(
        val year: Int = 0,
        val goalBooks: Int? = null,
        val completedBooks: Int = 0,
        val projectedBooks: Double = 0.0,
        val status: String? = null,
        val points: List<Point> = emptyList(),
    ) {
        @Serializable data class Point(val month: Int, val actualCumulative: Double = 0.0, val targetCumulative: Double? = null)
    }
    @Serializable
    data class Rhythm(
        val weekdays: List<Weekday> = emptyList(),
        val hours: List<Hour> = emptyList(),
        val favoriteDayOfWeek: Int? = null,
        val peakHour: Int? = null,
    ) {
        @Serializable data class Weekday(val dayOfWeek: Int, val averageSeconds: Double = 0.0)
        @Serializable data class Hour(val hour: Int, val totalSeconds: Double = 0.0)
    }
    @Serializable
    data class Completion(val months: List<Month> = emptyList()) {
        @Serializable data class Month(val year: Int, val month: Int, val count: Int = 0)
    }
    @Serializable
    data class Sources(val totals: ActivityDuration = ActivityDuration(), val slices: List<Slice> = emptyList()) {
        @Serializable data class Slice(val bucket: String, val totalSeconds: Double = 0.0)
    }
    @Serializable
    data class Pace(val eligibleSessions: Int = 0, val medianDurationSeconds: Double? = null, val medianProgressDelta: Double? = null)
}

// ---- Achievements ----

@Serializable
data class AchievementItem(
    val key: String,
    val groupKey: String? = null,
    val tier: Int? = null,
    val category: String = "",
    val name: String = "",
    val description: String = "",
    val iconName: String = "",
    val rarity: String = "common",
    val threshold: Double? = null,
    val hidden: Boolean = false,
    val sortOrder: Int? = null,
    val earned: Boolean = false,
    val awardedAt: String? = null,
    val currentProgress: Double? = null,
)

@Serializable
data class AchievementCategory(
    val key: String,
    val label: String = "",
    val earnedCount: Int = 0,
    val totalCount: Int = 0,
    val achievements: List<AchievementItem> = emptyList(),
)

@Serializable
data class AchievementCatalogue(
    val categories: List<AchievementCategory> = emptyList(),
    val totalEarned: Int = 0,
    val totalAvailable: Int = 0,
)

// ---- Highlights hub ----

@Serializable
data class HubHighlight(
    val id: Long,
    val bookId: Long = 0,
    val text: String = "",
    val color: String = "yellow",
    val style: String = "highlight",
    val note: String? = null,
    val chapterTitle: String? = null,
    val origin: String = "web",
    val highlightedAt: String? = null,
    val createdAt: String? = null,
    val bookTitle: String? = null,
    val author: String? = null,
    val starredAt: String? = null,
)

@Serializable
data class HubPage(val items: List<HubHighlight> = emptyList(), val total: Int = 0, val page: Int = 1, val pageSize: Int = 50)

@Serializable
data class HubOverview(
    val total: Int = 0,
    val books: Int = 0,
    val withNotes: Int = 0,
    val colorBreakdown: List<ColorCount> = emptyList(),
) {
    @Serializable data class ColorCount(val color: String, val count: Int = 0)
}

// ---- Book page ----

@Serializable
data class BookDetail(
    val id: Long,
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val isbn10: String? = null,
    val isbn13: String? = null,
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val language: String? = null,
    val pageCount: Int? = null,
    val seriesId: Long? = null,
    val seriesName: String? = null,
    val seriesIndex: JsonElement? = null,
    val rating: Double? = null,
    val personalNote: String? = null,
    val libraryName: String? = null,
    val addedAt: String? = null,
    val authors: List<Author> = emptyList(),
    val genres: List<String> = emptyList(),
    val files: List<File> = emptyList(),
    val collections: List<Collection> = emptyList(),
    val readStatus: Status? = null,
) {
    @Serializable data class Author(val id: Long, val name: String)
    @Serializable data class File(val id: Long, val format: String? = null, val role: String? = null, val sizeBytes: Long? = null, val durationSeconds: Double? = null)
    @Serializable data class Collection(val id: Long, val name: String)
    @Serializable data class Status(val status: String, val startedAt: String? = null, val finishedAt: String? = null)
}

/** A book in the "More in series", "More by" and "Similar books" rows. */
@Serializable
data class RelatedBook(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val seriesIndex: JsonElement? = null,
    val hasCover: Boolean = true,
) {
    /** Enough of a card to open the book's own page. */
    fun card() = BookCard(id = id, title = title, authors = authors, hasCover = hasCover)
}

@Serializable
data class JournalEntry(
    val id: Long,
    val clientId: String = "",
    val body: String = "",
    val quote: String? = null,
    val chapterTitle: String? = null,
    val positionPercent: Double? = null,
    val createdAt: String? = null,
)

@Serializable
data class SessionsPage(
    val items: List<Item> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 100,
    val stats: Stats = Stats(),
) {
    @Serializable
    data class Item(
        val id: Long,
        val startedAt: String,
        val endedAt: String = "",
        val durationSeconds: Double = 0.0,
        val progressDelta: Double? = null,
        val endProgress: Double? = null,
        val source: String? = null,
    )

    @Serializable
    data class Stats(
        val totalSessions: Int = 0,
        val totalSeconds: Double = 0.0,
        val firstSessionAt: String? = null,
        val lastSessionAt: String? = null,
        val bySource: List<Slice> = emptyList(),
        val dailySummary: List<Day> = emptyList(),
        val progressSummary: List<Progress> = emptyList(),
        val paceProgressDelta: Double = 0.0,
        val paceDurationSeconds: Double = 0.0,
        val latestEndProgress: Double? = null,
    ) {
        @Serializable data class Slice(val bucket: String, val totalSeconds: Double = 0.0, val totalSessions: Int = 0)
        @Serializable data class Day(val day: String, val totalMinutes: Double = 0.0)
        @Serializable data class Progress(val day: String, val endProgress: Double = 0.0)
    }
}
