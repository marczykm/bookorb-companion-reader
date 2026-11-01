package app.companion.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // The same setting the reader checks on every key press, so a change applies straight away.
    val settings = remember { ReaderSettings(context) }
    var volumeKeys by remember { mutableStateOf(settings.volumeKeys) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Text("Reading", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { volumeKeys = !volumeKeys; settings.volumeKeys = volumeKeys }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Turn pages with the volume keys", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Volume down goes to the next page and volume up to the previous one while a book is open.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = volumeKeys, onCheckedChange = { volumeKeys = it; settings.volumeKeys = it })
            }
        }
    }
}
