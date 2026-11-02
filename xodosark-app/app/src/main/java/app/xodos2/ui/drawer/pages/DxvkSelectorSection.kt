package app.xodos2.ui.drawer.pages

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.WineComponentManager
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.xodos2.R

@Composable
fun DxvkSelectorSection(
    category: String = "d3d",
    title: String = "D3D Components",
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var archives by remember { mutableStateOf(WineComponentManager.listArchives(context, category)) }
    var selected by remember { mutableStateOf(WineComponentManager.lastSelected(context, category)) }
    var busy     by remember { mutableStateOf(false) }
    var status   by remember { mutableStateOf<String?>(null) }

    // Refresh whenever the section is (re)composed
    LaunchedEffect(category) {
        archives = WineComponentManager.listArchives(context, category)
        val saved = WineComponentManager.lastSelected(context, category)
        // If saved selection no longer exists, drop it
        selected = saved?.takeIf { name -> archives.any { it.name == name } }
    }

    DrawerExpandableSection(title = title, defaultExpanded = false) {
        if (archives.isEmpty()) {
            Text(
                text = stringResource(R.string.appscreen_no_archives_found_in, category),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            return@DrawerExpandableSection
        }

        archives.forEach { archive ->
            val isSelected = archive.name == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled && !busy) {
                        scope.launch {
                            // Persist immediately
                            selected = archive.name
                            WineComponentManager.saveSelected(context, category, archive.name)

                            // Extract into the Wine prefix
                            busy = true
                            status = "Extracting ${archive.name}..."
                            val ok = WineComponentManager.extractToWinePrefix(
                                context = context,
                                category = category,
                                archiveName = archive.name
                            ) { _, msg -> status = msg }
                            busy = false
                            status = if (ok) "Installed ${archive.name}" else "Failed: ${archive.name}"
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    enabled = enabled && !busy
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = archive.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (busy && isSelected) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}
