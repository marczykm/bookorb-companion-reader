package app.companion.reader

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

private val SHELVES = listOf(
    "continue" to "continue-reading",
    "next" to "up-next-in-series",
    "want" to "want-to-read",
    "recent" to "recently-added",
)
private val SHELF_TITLES = mapOf(
    "next" to "Up next in series",
    "want" to "Want to read",
    "recent" to "Recently added",
)

fun openBook(context: Context, book: BookCard): Boolean {
    val epub = book.epubFile() ?: return false
    context.startActivity(Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_FILE_ID, epub.id).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id))
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(app: App, onLogout: () -> Unit, onOpenSettings: () -> Unit, onOpenApps: (() -> Unit)? = null, onOpenBook: (BookCard) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var shelves by remember { mutableStateOf<Map<String, List<BookCard>>>(emptyMap()) }
    var widgets by remember { mutableStateOf<Map<String, kotlinx.serialization.json.JsonObject>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var showAbout by remember { mutableStateOf(false) }
    var customizing by remember { mutableStateOf(false) }
    val layout = remember { HomeLayout(context) }
    if (customizing) CustomizeHomeSheet(layout) { customizing = false }
    if (showAbout) AboutDialog { showAbout = false }

    fun load() {
        if (loading) return
        loading = true; error = null
        scope.launch {
            try {
                shelves = app.api.shelves(SHELVES)
                widgets = app.api.widgets(WIDGET_TYPES)
            } catch (e: AuthException) {
                app.session.clearTokens(); onLogout()
            } catch (e: Exception) {
                error = e.message ?: "Failed to load"
            } finally {
                loading = false
            }
        }
    }

    // Reload each time the screen comes back (e.g. after closing the reader) so progress is current.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) load()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BackgroundRefresh(app, failing = error != null, refresh = { load() })

    fun open(book: BookCard) {
        if (!openBook(context, book)) scope.launch { snackbar.showSnackbar("Only EPUB books can be read in the app") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = {
                    OfflineIcon(app)
                    IconButton(onClick = { customizing = true }) { Icon(Icons.Default.Edit, "Customize Home") }
                    IconButton(onClick = { showAbout = true }) { Icon(Icons.Default.Info, "About") }
                    IconButton(onClick = { load() }) { Icon(Icons.Default.Refresh, "Refresh") }
                    onOpenApps?.let { open -> IconButton(onClick = open) { Icon(Icons.Default.Apps, "All apps") } }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = { scope.launch { app.api.logout(); onLogout() } }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, "Sign out")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val current = shelves["continue"].orEmpty()
            LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(layout.rows, key = { it.first() }) { row ->
                    if (row.size == 2 || (row.size == 1 && row[0] in HALF_WIDTH_ITEMS && layout.sizeOf(row[0]) == "s")) {
                        Row(Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { id ->
                                Box(Modifier.weight(1f).fillMaxHeight()) { WidgetItem(id, widgets, current.isNotEmpty(), "s") }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    } else {
                        val id = row[0]
                        val size = layout.sizeOf(id)
                        when {
                            id == "hero" -> {
                                if (current.isNotEmpty()) ContinueCard(app, current.first(), size, onOpen = { onOpenBook(current.first()) }) { open(current.first()) }
                                else if (!loading && error == null) {
                                    Text("Nothing in progress. Pick a book from the library.", Modifier.padding(horizontal = 16.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            id == "shelf:continue" -> if (current.size > 1) Shelf(app, "Continue reading", current.drop(1), size, onOpenBook)
                            id.startsWith("shelf:") -> {
                                val key = id.removePrefix("shelf:")
                                val books = shelves[key].orEmpty()
                                if (books.isNotEmpty()) Shelf(app, SHELF_TITLES.getValue(key), books, size, onOpenBook)
                            }
                            widgets.isNotEmpty() -> Box(Modifier.padding(horizontal = 16.dp)) { WidgetItem(id, widgets, current.isNotEmpty(), size) }
                        }
                    }
                }
            }
            if (loading && shelves.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
            OfflineEmpty(app, hasContent = shelves.isNotEmpty() || widgets.isNotEmpty(), failed = error != null)
        }
    }
}

@Composable
private fun ContinueCard(app: App, book: BookCard, size: String, onOpen: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onOpen), border = Display.cardBorder()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(
                model = app.api.thumbnailUrl(book.id),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                colorFilter = Display.imageFilter,
                modifier = Modifier.width(coverWidth(size, 110)).aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small),
            )
            Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
                Text("Continue reading", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(book.title ?: "Untitled", style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(book.authors.joinToString(), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                book.readingProgress?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { (p / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${p.toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClick) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun Shelf(app: App, title: String, books: List<BookCard>, size: String, onOpen: (BookCard) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { it.id }) { book ->
                Box(Modifier.width(coverWidth(size, 110))) { BookTile(app, book) { onOpen(book) } }
            }
        }
    }
}

/** Notice required by BookOrbit's additional license terms for material reused from the project. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        modifier = with(Display) { Modifier.dialogBorder(MaterialTheme.shapes.extraLarge) },
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("About") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Powered by BookOrbit", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/bookorbit/bookorbit")))
                    })
                Text("Copyright (C) 2025-2026 neon. Additional copyright is held by the respective BookOrbit contributors.")
                Text("Original project: https://github.com/bookorbit/bookorbit")
                Text("Licensed under the GNU Affero General Public License, version 3 only, with additional terms. " +
                    "This software is provided without warranty. You may redistribute and modify it under those terms.")
                Text("This is a modified version of BookOrbit and is not an official BookOrbit release. " +
                    "Companion artwork is from the BookOrbit KOReader plugin. EPUB position code is from foliate-js (MIT).")
            }
        },
    )
}

/** Cover width for an item size: small is about three quarters of medium, large about a third bigger. */
private fun coverWidth(size: String, medium: Int) = when (size) { "s" -> medium * 3 / 4; "l" -> medium * 4 / 3; else -> medium }.dp
