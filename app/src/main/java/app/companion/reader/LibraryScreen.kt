package app.companion.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val STATUSES = listOf(
    "reading" to "Reading", "want_to_read" to "Want to read", "unread" to "Unread",
    "read" to "Read", "on_hold" to "On hold", "abandoned" to "Abandoned",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(app: App, onLogout: () -> Unit, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val books = remember { mutableStateListOf<BookCard>() }
    var libraries by remember { mutableStateOf<List<Library>>(emptyList()) }
    var libraryId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var gridView by rememberSaveable { mutableStateOf(true) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BookCard?>(null) }
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    fun load(reset: Boolean) {
        if (loading && !reset) return
        loading = true; error = null
        val target = if (reset) 0 else page + 1
        scope.launch {
            try {
                val result = app.api.books(query, target, libraryId = libraryId, status = status)
                if (reset) books.clear()
                books.addAll(result.items)
                total = result.total
                page = target
            } catch (e: AuthException) {
                app.session.clearTokens(); onLogout()
            } catch (e: Exception) {
                error = e.message ?: "Failed to load books"
            } finally {
                loading = false
            }
        }
    }

    BackgroundRefresh(app, failing = error != null, refresh = { load(true) })
    LaunchedEffect(Unit) { libraries = runCatching { app.api.libraries() }.getOrDefault(emptyList()) }
    // Filters and search restart the listing; typing is debounced.
    LaunchedEffect(query, libraryId, status) {
        if (query.isNotEmpty()) delay(400)
        load(true)
    }
    val nearEnd by remember { derivedStateOf { if (gridView) grid.nearEnd(books.size) else list.nearEnd(books.size) } }
    LaunchedEffect(nearEnd) { if (nearEnd && books.size < total && !loading) load(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(query, { query = it }, singleLine = true, placeholder = { Text("Search books") },
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
                    } else Text("Library ($total)")
                },
                actions = {
                    OfflineIcon(app)
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Search") }
                    IconButton(onClick = { gridView = !gridView }) {
                        Icon(if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "Switch view")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = { scope.launch { app.api.logout(); onLogout() } }) { Icon(Icons.AutoMirrored.Filled.Logout, "Sign out") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineBanner(app, books.isNotEmpty())
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(libraryId == null, { libraryId = null }, { Text("All libraries") }) }
                items(libraries, key = { it.id }) { lib ->
                    FilterChip(libraryId == lib.id, { libraryId = lib.id }, { Text(lib.name) })
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(status == null, { status = null }, { Text("Any status") }) }
                items(STATUSES) { (key, label) -> FilterChip(status == key, { status = if (status == key) null else key }, { Text(label) }) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (gridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp), state = grid,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(books, key = { it.id }) { book ->
                            BookTile(app, book, onDevice = book.epubFile()?.let { app.api.isOnDevice(it.id) } == true) { selected = book }
                        }
                    }
                } else {
                    LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(books, key = { it.id }) { book -> BookRow(app, book) { selected = book } }
                    }
                }
                if (loading && books.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (!loading && books.isEmpty() && error == null) {
                    Text("No books found", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OfflineEmpty(app, hasContent = books.isNotEmpty(), failed = error != null)
            }
        }
    }

    selected?.let { book ->
        BookSheet(app, book, onDismiss = { selected = null }, onChanged = { updated ->
            val i = books.indexOfFirst { it.id == updated.id }
            if (i >= 0) books[i] = updated
            selected = updated
        }, onRead = {
            if (openBook(context, book)) selected = null
            else scope.launch { snackbar.showSnackbar("Only EPUB books can be read in the app") }
        })
    }
}

private fun LazyGridState.nearEnd(count: Int): Boolean {
    val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return count > 0 && last >= count - 6
}

private fun androidx.compose.foundation.lazy.LazyListState.nearEnd(count: Int): Boolean {
    val last = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return count > 0 && last >= count - 5
}

@Composable
private fun BookRow(app: App, book: BookCard, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model = app.api.thumbnailUrl(book.id), contentDescription = book.title, contentScale = ContentScale.Crop,
                colorFilter = Display.imageFilter,
            modifier = Modifier.width(56.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small),
        )
        Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(book.title ?: "Untitled", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(book.authors.joinToString(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val extra = listOfNotNull(book.seriesName, book.readStatus?.status?.let { s -> STATUSES.firstOrNull { it.first == s }?.second })
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (book.epubFile()?.let { app.api.isOnDevice(it.id) } == true) {
            Icon(Icons.Default.CheckCircle, "On device",
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically).size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookSheet(app: App, book: BookCard, onDismiss: () -> Unit, onChanged: (BookCard) -> Unit, onRead: () -> Unit) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(shape = Display.sheetShape(), dragHandle = { Display.SheetHandle() }, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = app.api.thumbnailUrl(book.id), contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = Display.imageFilter,
                    modifier = Modifier.width(90.dp).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small),
                )
                Column(Modifier.align(Alignment.CenterVertically)) {
                    Text(book.title ?: "Untitled", style = MaterialTheme.typography.titleMedium)
                    Text(book.authors.joinToString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    book.seriesName?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    book.readingProgress?.let { Text("${it.toInt()}% read", style = MaterialTheme.typography.labelMedium) }
                }
            }
            Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                Text(if ((book.readingProgress ?: 0.0) > 0) "Continue reading" else "Read")
            }

            val epub = book.epubFile()
            var downloaded by remember(book.id) { mutableStateOf(epub?.let { app.api.isOnDevice(it.id) } == true) }
            var downloading by remember(book.id) { mutableStateOf(false) }
            var downloadFailed by remember(book.id) { mutableStateOf(false) }
            if (epub != null && downloaded) {
                Text("Available offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else if (epub != null) {
                OutlinedButton(
                    enabled = !downloading, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        downloading = true; downloadFailed = false
                        scope.launch {
                            runCatching { app.api.downloadFile(epub.id, "epub") }.onSuccess { downloaded = true }.onFailure { downloadFailed = true }
                            downloading = false
                        }
                    },
                ) { Text(if (downloading) "Downloading…" else "Download for offline") }
                if (downloadFailed) Text("Couldn't download right now.", style = MaterialTheme.typography.labelMedium)
            }

            Text("Status", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(STATUSES) { (key, label) ->
                    FilterChip(book.readStatus?.status == key, {
                        app.outbox.status(book.id, key); app.syncNow()
                        onChanged(book.copy(readStatus = StatusInfo(key)))
                    }, { Text(label) })
                }
            }

            Text("Rating", style = MaterialTheme.typography.labelLarge)
            Row {
                val current = book.rating?.toInt() ?: 0
                for (star in 1..5) {
                    IconButton(onClick = {
                        // Tapping the current rating clears it.
                        val new = if (star == current) null else star
                        app.outbox.rating(book.id, new); app.syncNow()
                        onChanged(book.copy(rating = new?.toDouble()))
                    }) {
                        Icon(if (star <= current) Icons.Default.Star else Icons.Default.StarBorder, "$star stars",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
