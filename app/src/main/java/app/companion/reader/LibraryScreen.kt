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
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GridView
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

internal val STATUSES = listOf(
    "reading" to "Reading", "want_to_read" to "Want to read", "unread" to "Unread",
    "read" to "Read", "on_hold" to "On hold", "abandoned" to "Abandoned",
)

/** Every book of the library, with library and status filters and a grid or list view; the "All" tab of Browse. */
@Composable
fun LibraryContent(app: App, query: String, onLogout: () -> Unit, onOpenBook: (BookCard) -> Unit) {
    val scope = rememberCoroutineScope()
    val books = remember { mutableStateListOf<BookCard>() }
    var libraries by remember { mutableStateOf<List<Library>>(emptyList()) }
    var libraryId by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var gridView by rememberSaveable { mutableStateOf(true) }
    var total by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val list = rememberLazyListState()

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

    Column(Modifier.fillMaxSize()) {
        OfflineBanner(app, books.isNotEmpty())
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(libraryId == null, { libraryId = null }, { Text("All libraries") }) }
                items(libraries, key = { it.id }) { lib ->
                    FilterChip(libraryId == lib.id, { libraryId = lib.id }, { Text(lib.name) })
                }
            }
            IconButton(onClick = { gridView = !gridView }) {
                Icon(if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "Switch view")
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
                        BookTile(app, book, onDevice = book.epubFile()?.let { app.api.isOnDevice(it.id) } == true) { onOpenBook(book) }
                    }
                }
            } else {
                LazyColumn(state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(books, key = { it.id }) { book -> BookRow(app, book) { onOpenBook(book) } }
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

