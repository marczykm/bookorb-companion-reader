package app.companion.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Dialogs and the keyboard can bring the bar back; hide it again whenever the app regains focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        hideStatusBar()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var loggedIn by remember { mutableStateOf(app.session.isLoggedIn) }
                    if (loggedIn) MainScreen(app, onLogout = { loggedIn = false })
                    else LoginScreen(app, onDone = { loggedIn = true })
                }
            }
        }
    }
}

@Composable
fun MainScreen(app: App, onLogout: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Search, null) }, label = { Text("Library") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Menu, null) }, label = { Text("Browse") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> HomeScreen(app, onLogout)
                1 -> LibraryScreen(app, onLogout)
                else -> BrowseScreen(app, onLogout)
            }
        }
    }
}

@Composable
fun LoginScreen(app: App, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(app.session.serverUrl) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || server.isBlank() || user.isBlank() || pass.isEmpty()) return
        busy = true; error = null
        scope.launch {
            try {
                app.api.login(server, user, pass)
                onDone()
            } catch (e: Exception) {
                error = e.message ?: "Login failed"
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("BookOrb: Companion reader", style = MaterialTheme.typography.headlineMedium)
        Text("An unofficial reader for your BookOrbit server", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && server.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(),
            onClick = { submit() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Signing in…" else "Sign in") }
    }
}

@Composable
fun BookTile(app: App, book: BookCard, onDevice: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = app.api.thumbnailUrl(book.id),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small),
            )
            if (onDevice) {
                Icon(androidx.compose.material.icons.Icons.Default.CheckCircle, "On device", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
            }
            val progress = book.readingProgress
            if (progress != null && progress > 0) {
                LinearProgressIndicator(
                    progress = { (progress / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
        Text(book.title ?: "Untitled", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(book.authors.joinToString(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
