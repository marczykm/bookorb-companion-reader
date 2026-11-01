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

    // The Home-app switch reflects what Android reports, and is read again whenever the page comes back
    // into view, since the system dialog and Android's own settings can change it.
    var homeOffered by remember { mutableStateOf(Launcher.isEnabled(context)) }
    var isDefaultHome by remember { mutableStateOf(Launcher.isDefaultHome(context)) }
    val refreshHome = { homeOffered = Launcher.isEnabled(context); isDefaultHome = Launcher.isDefaultHome(context) }
    val roleDialog = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { refreshHome() }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshHome() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

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

            Text("Home screen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use as the Home app", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            isDefaultHome -> "Pressing Home opens this app. Open All apps from the Home tab to start other apps."
                            homeOffered -> "Offered as a Home app, but Android hasn't made it the default yet."
                            else -> "Off. Pressing Home opens your usual launcher."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = homeOffered, onCheckedChange = { on ->
                    Launcher.setEnabled(context, on)
                    refreshHome()
                    // Android picks the default Home app, so ask it; it shows its own confirmation.
                    if (on) roleDialog.launch(Launcher.requestIntent(context))
                })
            }
            if (homeOffered && !isDefaultHome) {
                TextButton(onClick = { context.startActivity(Launcher.settingsIntent()) }) { Text("Choose the Home app in Android settings") }
            }
        }
    }
}
