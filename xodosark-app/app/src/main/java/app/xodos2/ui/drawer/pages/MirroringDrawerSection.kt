package app.xodos2.ui.drawer.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.xodos2.R
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.mirror.MirrorSettingsDialog

@Composable
fun MirroringDrawerSection(
    title: String? = null,
    enabled: Boolean = true
) {
    var showDialog by remember { mutableStateOf(false) }
    val sectionTitle = title ?: stringResource(R.string.app_mirror_section_title)

    DrawerExpandableSection(title = sectionTitle, defaultExpanded = false) {
        Text(
            stringResource(R.string.app_mirror_section_description),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Button(
            onClick = { showDialog = true },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(stringResource(R.string.app_mirror_open_settings))
        }
    }

    if (showDialog) {
        MirrorSettingsDialog(onDismiss = { showDialog = false })
    }
}