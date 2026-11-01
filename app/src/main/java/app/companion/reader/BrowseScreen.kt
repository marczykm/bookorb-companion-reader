package app.companion.reader

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A series or an author that was opened to see its books. */
private data class Opened(val kind: Int, val id: Long, val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(app: App, onLogout: () -> Unit) {
    var opened by remember { mutableStateOf<Opened?>(null) }
    BackHandler(enabled = opened != null) { opened = null }
    opened?.let { o ->
        BooksOf(app, o, onBack = { opened = null }, onLogout = onLogout)
        return
    }

    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) } // 0 series, 1 authors, 2 SmartScopes
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
        if (loading && !reset) return
        loading = true; error = null
        val target = if (reset) 0 else page + 1
        scope.launch {
            try {
                when (mode) {
                    0 -> {
                        val r = app.api.series(query, target)
                        if (reset) series.clear(); series.addAll(r.items); total = r.total
                    }
                    1 -> {
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

    BackgroundRefresh(app, failing = error != null, refresh = { load(true) })
    LaunchedEffect(mode, query) {
        if (query.isNotEmpty()) delay(400)
        load(true)
    }
    val count = when (mode) { 0 -> series.size; 1 -> authors.size; else -> scopes.size }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= count - 5 && count > 0 } }
    LaunchedEffect(nearEnd) { if (nearEnd && count < total && !loading) load(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Browse") }, actions = { OfflineIcon(app) }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineBanner(app, count > 0)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SegmentedButton(mode == 0, { mode = 0 }, SegmentedButtonDefaults.itemShape(0, 3)) { Text("Series") }
                SegmentedButton(mode == 1, { mode = 1 }, SegmentedButtonDefaults.itemShape(1, 3)) { Text("Authors") }
                SegmentedButton(mode == 2, { mode = 2 }, SegmentedButtonDefaults.itemShape(2, 3)) { Text("SmartScopes", maxLines = 1) }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(when (mode) { 0 -> "Search series"; 1 -> "Search authors"; else -> "Search SmartScopes" }) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode == 0) items(series, key = { it.id }) { s ->
                        BrowseRow(app, s.coverBookIds.firstOrNull(), s.name,
                            "${s.readCount}/${s.bookCount} read" + s.authors.takeIf { it.isNotEmpty() }?.let { " · " + it.joinToString() }.orEmpty(),
                            round = false) { opened = Opened(0, s.id, s.name) }
                    } else if (mode == 1) items(authors, key = { it.id }) { a ->
                        BrowseRow(app, a.coverBookId, a.name, "${a.bookCount} book" + if (a.bookCount == 1) "" else "s", round = true) {
                            opened = Opened(1, a.id, a.name)
                        }
                    } else items(scopes, key = { it.id }) { sc ->
                        BrowseRow(app, null, sc.name, sc.bookCount?.let { "$it book" + if (it == 1) "" else "s" } ?: "SmartScope", round = false) {
                            opened = Opened(2, sc.id, sc.name)
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

@Composable
private fun BrowseRow(app: App, coverBookId: Long?, title: String, subtitle: String, round: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val shape = if (round) CircleShape else MaterialTheme.shapes.small
        if (coverBookId != null) {
            AsyncImage(
                model = app.api.thumbnailUrl(coverBookId), contentDescription = null, contentScale = ContentScale.Crop,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BooksOf(app: App, o: Opened, onBack: () -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val books = remember { mutableStateListOf<BookCard>() }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<BookCard?>(null) }
    val grid = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }

    fun load(reset: Boolean) {
        if (loading && !reset) return
        loading = true; error = null
        val target = if (reset) 0 else page + 1
        scope.launch {
            try {
                val r = when (o.kind) {
                    0 -> app.api.seriesBooks(o.id, target)
                    1 -> app.api.authorBooks(o.id, target)
                    else -> app.api.smartScopeBooks(o.id, target)
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
    LaunchedEffect(o) { load(true) }
    BackgroundRefresh(app, failing = error != null, refresh = { load(true) })
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= books.size - 6 && books.isNotEmpty() } }
    LaunchedEffect(nearEnd) { if (nearEnd && books.size < total && !loading) load(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(o.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { OfflineIcon(app) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp), state = grid, contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { OfflineBanner(app, books.isNotEmpty()) }
                items(books, key = { it.id }) { book ->
                    BookTile(app, book, onDevice = book.epubFile()?.let { app.api.isOnDevice(it.id) } == true) { selected = book }
                }
            }
            if (loading && books.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
            OfflineEmpty(app, hasContent = books.isNotEmpty(), failed = error != null)
        }
    }

    selected?.let { book ->
        BookSheet(app, book, onDismiss = { selected = null }, onChanged = { u ->
            val i = books.indexOfFirst { it.id == u.id }
            if (i >= 0) books[i] = u
            selected = u
        }, onRead = {
            if (openBook(context, book)) selected = null
            else scope.launch { snackbar.showSnackbar("Only EPUB books can be read in the app") }
        })
    }
}
