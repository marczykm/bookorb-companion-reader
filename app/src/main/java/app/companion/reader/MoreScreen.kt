package app.companion.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** History, Activity and Highlights, which have no tab of their own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(app: App, onOpenSettings: () -> Unit, go: (Dest) -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("More") }, actions = {
            OfflineIcon(app)
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MoreRow("History", "Finished reads by month", Icons.Default.History) { go(Dest.History) }
            HorizontalDivider()
            MoreRow("Activity", "Reading insights and achievements", Icons.Default.BarChart) { go(Dest.Activity) }
            HorizontalDivider()
            MoreRow("Highlights", "Everything you have highlighted", Icons.Default.Edit) { go(Dest.Highlights) }
        }
    }
}

@Composable
private fun MoreRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
