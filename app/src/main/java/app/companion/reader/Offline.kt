package app.companion.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Keeps a screen fresh without the user doing anything: refreshes when a connection comes back and,
 * while the server can't be reached, quietly tries again every half minute.
 */
@Composable
fun BackgroundRefresh(app: App, failing: Boolean, refresh: () -> Unit) {
    val latest by rememberUpdatedState(refresh)
    val networkUp by app.networkUp.collectAsState()
    val serverReachable by app.api.online.collectAsState()
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(networkUp) {
        if (first) first = false else if (networkUp) latest()
    }
    LaunchedEffect(serverReachable, failing) {
        while (!serverReachable || failing) {
            delay(30_000)
            latest()
        }
    }
}

/** A crossed-out Wi-Fi symbol for the top bar, shown while the server can't be reached. No text, nothing to press. */
@Composable
fun OfflineIcon(app: App) {
    val serverReachable by app.api.online.collectAsState()
    if (!serverReachable) {
        Icon(
            Icons.Default.WifiOff, contentDescription = "Offline",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp).size(22.dp),
        )
    }
}

/** A thin line of text above saved data while the server can't be reached (Library and Browse; Home uses only the icon). */
@Composable
fun OfflineBanner(app: App, hasContent: Boolean) {
    val serverReachable by app.api.online.collectAsState()
    if (hasContent && !serverReachable) {
        Text(
            "Offline — showing saved data. Changes sync when the server is back.",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shown where data would be when nothing was ever loaded; fills in by itself later. */
@Composable
fun OfflineEmpty(app: App, hasContent: Boolean, failed: Boolean) {
    if (!hasContent && failed) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.WifiOff, contentDescription = "Offline", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp))
        }
    }
}
