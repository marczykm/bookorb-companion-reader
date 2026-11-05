package app.companion.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the app can go beyond the three tabs; the stack of them lives in [MainScreen]. */
sealed interface Dest {
    data class Book(val book: BookCard) : Dest
    data class Collection(val kind: Int, val id: Long, val title: String) : Dest   // 0 series, 1 author, 2 SmartScope
    data class Timeline(val book: BookCard, val title: String, val sessions: SessionsPage) : Dest
    data object History : Dest
    data object Activity : Dest
    data object Highlights : Dest
}

/** Time left at the reader's own pace, from the server's pace figures; null until there is enough reading to go on. */
fun secondsLeft(st: SessionsPage.Stats, progress: Double?): Double? {
    if (progress == null || st.paceProgressDelta <= 0 || st.paceDurationSeconds <= 0) return null
    return (100 - progress).coerceAtLeast(0.0) / st.paceProgressDelta * st.paceDurationSeconds
}

/** The page of one book: cover, reading progress, details, notes and related books. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookPage(app: App, initial: BookCard, onBack: () -> Unit, onLogout: () -> Unit, go: (Dest) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var book by remember(initial.id) { mutableStateOf(initial) }
    var detail by remember(initial.id) { mutableStateOf<BookDetail?>(null) }
    var sessions by remember(initial.id) { mutableStateOf<SessionsPage?>(null) }
    var journal by remember(initial.id) { mutableStateOf<List<JournalEntry>>(emptyList()) }
    var inSeries by remember(initial.id) { mutableStateOf<List<RelatedBook>>(emptyList()) }
    var byAuthor by remember(initial.id) { mutableStateOf<List<RelatedBook>>(emptyList()) }
    var similar by remember(initial.id) { mutableStateOf<List<RelatedBook>>(emptyList()) }
    var downloading by remember(initial.id) { mutableStateOf(false) }
    var onDevice by remember(initial.id) { mutableStateOf(false) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var entryDialog by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // The EPUB can come from the card or, for books opened from a list that has none, from the full record.
    val epub: BookFileRef? = book.epubFile() ?: detail?.files?.firstOrNull { it.format.equals("epub", true) || it.format.equals("kepub", true) }
        ?.let { BookFileRef(it.id, it.format, it.role ?: "") }
    val progress: Double? = sessions?.stats?.latestEndProgress ?: book.readingProgress
    val title = detail?.title ?: book.title ?: "Untitled"
    val authors = detail?.authors?.map { it.name }?.takeIf { it.isNotEmpty() }?.joinToString() ?: book.authors.joinToString()

    fun load() {
        scope.launch {
            epub?.let { onDevice = app.api.isOnDevice(it.id) }
            try {
                coroutineScope {
                    val d = async { runCatching { app.api.bookDetail(book.id) }.getOrNull() }
                    val s = async { runCatching { app.api.sessions(book.id) }.getOrNull() }
                    val j = async { app.api.journal(book.id) }
                    detail = d.await(); sessions = s.await(); journal = j.await()
                }
                epub?.let { onDevice = app.api.isOnDevice(it.id) }
                coroutineScope {
                    val a = async { app.api.seriesBooks(book.id) }
                    val b = async { app.api.authorBooks(book.id) }
                    val c = async { app.api.recommendations(book.id) }
                    inSeries = a.await(); byAuthor = b.await(); similar = c.await()
                }
            } catch (e: AuthException) {
                app.session.clearTokens(); onLogout()
            }
        }
    }
    LaunchedEffect(initial.id) { load() }
    // Coming back from the reader: progress and sessions have changed.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && detail != null) load() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (entryDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            modifier = with(Display) { Modifier.dialogBorder(MaterialTheme.shapes.extraLarge) },
            onDismissRequest = { entryDialog = false }, title = { Text("New entry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("It is dated and remembers where you were in the book.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it }, label = { Text("What do you think?") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    entryDialog = false
                    val body = text.trim()
                    if (body.isNotEmpty()) scope.launch {
                        runCatching { app.api.addJournalEntry(book.id, body, progress); journal = app.api.journal(book.id) }
                            .onFailure { error = it.message }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { entryDialog = false }) { Text("Cancel") } },
        )
    }
    if (noteDialog) {
        var text by remember { mutableStateOf(detail?.personalNote.orEmpty()) }
        AlertDialog(
            modifier = with(Display) { Modifier.dialogBorder(MaterialTheme.shapes.extraLarge) },
            onDismissRequest = { noteDialog = false }, title = { Text("My review") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Private note") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    noteDialog = false
                    val note = text.trim().ifEmpty { null }
                    scope.launch {
                        runCatching { app.api.setPersonalNote(book.id, note); detail = detail?.copy(personalNote = note) }.onFailure { error = it.message }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Cancel") } },
        )
    }

    SubScreen(title, onBack, actions = { OfflineIcon(app) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // ---- Hero ----
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cover(app, book.id, Modifier.width(180.dp).aspectRatio(2f / 3f))
                    Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center)
                    val firstAuthor = detail?.authors?.firstOrNull()
                    if (firstAuthor != null) {
                        Text("$authors ›", Modifier.clickable { go(Dest.Collection(1, firstAuthor.id, firstAuthor.name)) }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                    } else if (authors.isNotEmpty()) Text(authors, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    detail?.seriesName?.let { name ->
                        val text = name + (detail?.seriesIndex.text()?.let { " · Book $it" } ?: "")
                        val sid = detail?.seriesId
                        if (sid != null) Text("$text ›", Modifier.clickable { go(Dest.Collection(0, sid, name)) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // ---- Read button ----
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (epub != null) {
                        val fraction = ((progress ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
                        Box(
                            Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                .clickable {
                                    if (!openBook(context, book.copy(files = listOf(epub)))) scope.launch { snackbar.showSnackbar("Only EPUB books can be read in the app") }
                                },
                        ) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MenuBook, null, Modifier.size(26.dp))
                                Column {
                                    Text(if ((progress ?: 0.0) > 0) "Continue Reading" else "Read", style = MaterialTheme.typography.titleMedium)
                                    Text(listOfNotNull("EPUB", progress?.let { "${it.roundToInt()}%" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else Text("Only EPUB books can be read in this app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                }

                // ---- Status and download ----
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        Tile(Icons.Default.MenuBook, (STATUSES.firstOrNull { it.first == (book.readStatus?.status ?: "unread") }?.second ?: "Status") + " ▾") { statusMenu = true }
                        DropdownMenu(statusMenu, { statusMenu = false }) {
                            STATUSES.forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    statusMenu = false
                                    app.outbox.status(book.id, key); app.syncNow()
                                    book = book.copy(readStatus = StatusInfo(key))
                                })
                            }
                        }
                    }
                    if (epub != null) Box(Modifier.weight(1f)) {
                        Tile(
                            if (onDevice) Icons.Default.CheckCircle else Icons.Default.Download,
                            when { downloading -> "Downloading…"; onDevice -> "Downloaded"; else -> "Download" },
                        ) {
                            if (downloading) return@Tile
                            if (onDevice) {
                                java.io.File(context.filesDir, "books/${epub.id}.epub").delete(); onDevice = false
                            } else {
                                downloading = true; error = null
                                scope.launch {
                                    runCatching { app.api.downloadFile(epub.id, "epub") }.onSuccess { onDevice = true }.onFailure { error = "Couldn't download right now." }
                                    downloading = false
                                }
                            }
                        }
                    }
                }

                // ---- Rating and facts ----
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Column(Modifier.weight(1.5f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Label("RATING")
                        Text(book.rating?.let { "%g".format(it) } ?: "Rate", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Row {
                            val current = book.rating?.toInt() ?: 0
                            for (star in 1..5) Icon(
                                if (star <= current) Icons.Default.Star else Icons.Default.StarBorder, "$star stars",
                                Modifier.size(24.dp).clickable {
                                    val new = if (star == current) null else star
                                    app.outbox.rating(book.id, new); app.syncNow()
                                    book = book.copy(rating = new?.toDouble())
                                },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Fact("LENGTH", detail?.pageCount?.toString() ?: "–", "pages", Modifier.weight(1f))
                    Fact("PUBLISHED", detail?.publishedYear?.toString() ?: "–", detail?.publisher.orEmpty(), Modifier.weight(1f))
                    Fact("LANGUAGE", detail?.language?.uppercase() ?: "–", detail?.language?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.ENGLISH) }.orEmpty(), Modifier.weight(1f))
                }

                // ---- Your reading ----
                sessions?.takeIf { it.stats.totalSessions > 0 }?.let { s ->
                    val p = progress ?: 0.0
                    Section("Your reading") {
                        InfoCard(null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                val primary = MaterialTheme.colorScheme.primary
                                val track = MaterialTheme.colorScheme.outlineVariant
                                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val inset = 4.dp.toPx()
                                        val arc = Size(size.width - 2 * inset, size.height - 2 * inset)
                                        drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(8.dp.toPx()))
                                        drawArc(primary, -90f, 360f * (p / 100).toFloat().coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                                    }
                                    Text("${p.roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                                }
                                Column {
                                    Label("EPUB")
                                    Text(secondsLeft(s.stats, progress)?.let { "About ${hm(it)} left" } ?: "In progress", style = MaterialTheme.typography.titleMedium)
                                    fullDate(s.stats.lastSessionAt)?.let { Text("Last read $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth()) {
                                StatBlock(hm(s.stats.totalSeconds), "read", Modifier.weight(1f))
                                StatBlock(fullDate(s.stats.firstSessionAt) ?: "–", "started", Modifier.weight(1f))
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().clickable { go(Dest.Timeline(book, title, s)) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Timeline ›", style = MaterialTheme.typography.titleSmall)
                                Text("${s.stats.totalSessions} sessions", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // ---- About ----
                detail?.let { d ->
                    if (!d.description.isNullOrEmpty() || d.genres.isNotEmpty()) Section("About this book") {
                        d.description?.takeIf { it.isNotEmpty() }?.let { Text(plainText(it), style = MaterialTheme.typography.bodyMedium) }
                        Chips(d.genres.take(6))
                    }
                    if (d.files.isNotEmpty()) Section("File") {
                        InfoCard(null) {
                            d.files.forEach { f ->
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Badge((f.format ?: "FILE").uppercase(), MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        val audio = f.format?.lowercase() in listOf("mp3", "m4b")
                                        Text("${(f.format ?: "File").uppercase()} ${if (audio) "audiobook" else "ebook"}", style = MaterialTheme.typography.titleSmall)
                                        f.sizeBytes?.let { Text(android.text.format.Formatter.formatShortFileSize(context, it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    if (onDevice && f.id == epub?.id) Text("✓ Downloaded", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    val rows = listOf(
                        "Series" to (d.seriesName?.let { it + (d.seriesIndex.text()?.let { i -> " #$i" } ?: "") } ?: ""),
                        "Publisher" to (d.publisher ?: ""),
                        "ISBN" to (d.isbn13 ?: d.isbn10 ?: ""),
                        "Library" to (d.libraryName ?: ""),
                        "Added" to (fullDate(d.addedAt) ?: ""),
                    ).filter { it.second.isNotEmpty() }
                    if (rows.isNotEmpty()) Section("Details") {
                        InfoCard(null) {
                            rows.forEachIndexed { i, (k, v) ->
                                if (i > 0) HorizontalDivider()
                                Row(Modifier.fillMaxWidth()) {
                                    Text(k, Modifier.width(100.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (k == "Series" && d.seriesId != null) Text("$v ›", Modifier.clickable { go(Dest.Collection(0, d.seriesId, d.seriesName.orEmpty())) }, color = MaterialTheme.colorScheme.primary)
                                    else Text(v)
                                }
                            }
                        }
                    }
                    if (d.collections.isNotEmpty()) Section("Collections") { Chips(d.collections.map { it.name }) }
                }

                // ---- Notebook and review ----
                Section("Notebook") {
                    InfoCard(null) {
                        if (journal.isEmpty()) Text("Jot down what you think as you read. Each entry is dated and remembers where you were in the book.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else journal.take(5).forEach { e ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(e.body)
                                Text(listOfNotNull(fullDate(e.createdAt), e.positionPercent?.let { "${it.roundToInt()}%" }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                        OutlinedButton(onClick = { entryDialog = true }, Modifier.fillMaxWidth()) { Text("New Entry") }
                    }
                }
                Section("My review") {
                    InfoCard(null, Modifier.clickable { noteDialog = true }) {
                        val note = detail?.personalNote?.takeIf { it.isNotEmpty() }
                        Text(note ?: "Add a private note", color = if (note == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }

                // ---- Related ----
                Related(app, "More in ${detail?.seriesName ?: "series"}", inSeries.filter { it.id != book.id }, showIndex = true, go)
                Related(app, "More by ${detail?.authors?.firstOrNull()?.name ?: "author"}", byAuthor.filter { it.id != book.id }, showIndex = false, go)
                Related(app, "Similar books", similar.filter { it.id != book.id }, showIndex = false, go)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Fact(label: String, value: String, sub: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(label)
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Tile(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), border = Display.cardBorder(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, Modifier.size(26.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(names: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant, border = Display.cardBorder()) { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge) } }
    }
}

@Composable
private fun Related(app: App, title: String, list: List<RelatedBook>, showIndex: Boolean, go: (Dest) -> Unit) {
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { r ->
                Column(Modifier.width(118.dp).clickable { go(Dest.Book(r.card())) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Cover(app, r.id, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    Text(r.title ?: "Untitled", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (showIndex) r.seriesIndex.text()?.let { "Book $it" }.orEmpty() else r.authors.joinToString(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
