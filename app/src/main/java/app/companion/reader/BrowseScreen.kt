package app.companion.reader


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(app: App, onLogout: () -> Unit, onOpenSettings: () -> Unit, go: (Dest) -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 all books, 1 series, 2 authors, 3 SmartScopes
    var query by remember { mutableStateOf("") }
    val series = remember { mutableStateListOf<SeriesItem>() }
    val authors = remember { mutableStateListOf<AuthorItem>() }
    val scopes = remember { mutableStateListOf<SmartScopeItem>() }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    fun load(reset: Boolean) {
        if (mode == 0) return   // All loads its own books
        if (loading && !reset) return
        loading = true; error = null
        val target = if (reset) 0 else page + 1
        scope.launch {
            try {
                when (mode) {
                    1 -> {
                        val r = app.api.series(query, target)
                        if (reset) series.clear(); series.addAll(r.items); total = r.total
                    }
                    2 -> {
                        val r = app.api.authors(query, target)
                        if (reset) authors.clear(); authors.addAll(r.items); total = r.total
                    }
                    else -> {
                        // SmartScopes come back in one piece; the search box filters them here.
                        val all = app.api.smartScopes().filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                        scopes.clear(); scopes.addAll(all); total = all.size
                    }
                }
                page = target
            } catch (e: AuthException) {
                app.session.clearTokens(); onLogout()
            } catch (e: Exception) {
                error = e.message ?: "Failed to load"
            } finally {
                loading = false
            }
        }
    }

    BackgroundRefresh(app, failing = error != null && mode != 0, refresh = { load(true) })
    LaunchedEffect(mode, query) {
        if (query.isNotEmpty()) delay(400)
        load(true)
    }
    val count = when (mode) { 1 -> series.size; 2 -> authors.size; 3 -> scopes.size; else -> 0 }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= count - 5 && count > 0 } }
    LaunchedEffect(nearEnd) { if (nearEnd && count < total && !loading) load(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Library") }, actions = {
            OfflineIcon(app)
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf("All", "Series", "Authors", "Scopes").forEachIndexed { i, label ->
                    SegmentedButton(mode == i, { mode = i }, SegmentedButtonDefaults.itemShape(i, 4)) { Text(label, maxLines = 1) }
                }
            }
            OutlinedTextField(query, { query = it }, singleLine = true,
                label = { Text(when (mode) { 0 -> "Search books"; 1 -> "Search series"; 2 -> "Search authors"; else -> "Search SmartScopes" }) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            if (mode == 0) {
                LibraryContent(app, query, onLogout) { go(Dest.Book(it)) }
            } else {
                OfflineBanner(app, count > 0)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (mode == 1) items(series, key = { it.id }) { s ->
                            BrowseRow(app, s.coverBookIds.firstOrNull(), s.name,
                                "${s.readCount}/${s.bookCount} read" + s.authors.takeIf { it.isNotEmpty() }?.let { " · " + it.joinToString() }.orEmpty(),
                                round = false) { go(Dest.Collection(0, s.id, s.name)) }
                        } else if (mode == 2) items(authors, key = { it.id }) { a ->
                            BrowseRow(app, a.coverBookId, a.name, "${a.bookCount} book" + if (a.bookCount == 1) "" else "s", round = true) {
                                go(Dest.Collection(1, a.id, a.name))
                            }
                        } else items(scopes, key = { it.id }) { sc ->
                            BrowseRow(app, null, sc.name, sc.bookCount?.let { "$it book" + if (it == 1) "" else "s" } ?: "SmartScope", round = false) {
                                go(Dest.Collection(2, sc.id, sc.name))
                            }
                        }
                    }
                    if (loading && count == 0) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    if (!loading && count == 0 && error == null) Text("Nothing found", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OfflineEmpty(app, hasContent = count > 0, failed = error != null)
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(app: App, coverBookId: Long?, title: String, subtitle: String, round: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val shape = if (round) CircleShape else MaterialTheme.shapes.small
        if (coverBookId != null) {
            AsyncImage(
                model = app.api.thumbnailUrl(coverBookId), contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = Display.imageFilter,
                modifier = Modifier.then(if (round) Modifier.size(56.dp) else Modifier.width(56.dp).aspectRatio(2f / 3f)).clip(shape),
            )
        } else {
            Box(Modifier.size(56.dp).clip(shape).then(Modifier), contentAlignment = Alignment.Center) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) { Text(title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The books of a series, author or SmartScope. */
@Composable
fun CollectionScreen(app: App, kind: Int, id: Long, title: String, onBack: () -> Unit, onLogout: () -> Unit, onOpenBook: (BookCard) -> Unit) {
    val scope = rememberCoroutineScope()
    val books = remember { mutableStateListOf<BookCard>() }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()

    fun load(reset: Boolean) {
        if (loading && !reset) return
        loading = true; error = null
        val target = if (reset) 0 else page + 1
        scope.launch {
            try {
                val r = when (kind) {
                    0 -> app.api.seriesBooks(id, target)
                    1 -> app.api.authorBooks(id, target)
                    else -> app.api.smartScopeBooks(id, target)
                }
                if (reset) books.clear()
                books.addAll(r.items); total = r.total; page = target
            } catch (e: AuthException) {
                app.session.clearTokens(); onLogout()
            } catch (e: Exception) {
                error = e.message ?: "Failed to load"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(kind, id) { load(true) }
    BackgroundRefresh(app, failing = error != null, refresh = { load(true) })
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= books.size - 6 && books.isNotEmpty() } }
    LaunchedEffect(nearEnd) { if (nearEnd && books.size < total && !loading) load(false) }

    SubScreen(title, onBack, actions = { OfflineIcon(app) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp), state = grid, contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { OfflineBanner(app, books.isNotEmpty()) }
                items(books, key = { it.id }) { book ->
                    BookTile(app, book, onDevice = book.epubFile()?.let { app.api.isOnDevice(it.id) } == true) { onOpenBook(book) }
                }
            }
            if (loading && books.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
            OfflineEmpty(app, hasContent = books.isNotEmpty(), failed = error != null)
        }
    }
}
