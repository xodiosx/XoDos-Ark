package app.xodos2.ui.drawer.pages

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.NativeInstallCoordinator

@Composable
fun ProotOptionsSection(prefs: SharedPreferences, containerId: Int) {
    val context = LocalContext.current
    val initial = remember(containerId) {
        NativeInstallCoordinator.loadProotOptions(context, containerId)
    }

    var username   by remember { mutableStateOf(initial.username.ifBlank { "root" }) }
    var hostname   by remember { mutableStateOf(initial.hostname.ifBlank { "localhost" }) }
    var kernel     by remember { mutableStateOf(initial.kernelRelease) }
    var sharedHome by remember { mutableStateOf(initial.sharedHome) }
    var isolated   by remember { mutableStateOf(initial.isolated) }
    var minimal    by remember { mutableStateOf(initial.minimal) }
    var noSysvipc  by remember { mutableStateOf(initial.noSysvipc) }
    var noLink2s   by remember { mutableStateOf(initial.noLink2symlink) }
    var changeId   by remember { mutableStateOf(initial.changeId) }
    var cwd        by remember { mutableStateOf(initial.cwd) }
    var bindsText  by remember { mutableStateOf(initial.binds.joinToString("\n")) }
    var envsText   by remember { mutableStateOf(initial.envs.joinToString("\n")) }

    fun persist() {
        val binds = bindsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val envs  = envsText.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains("=") }
        NativeInstallCoordinator.saveProotOptions(
            context, containerId,
            NativeInstallCoordinator.ProotOptions(
                username = username,
                hostname = hostname,
                sharedHome = sharedHome,
                sharedTmp = true,
                kernelRelease = kernel,
                isolated = isolated,
                minimal = minimal,
                noSysvipc = noSysvipc,
                noLink2symlink = noLink2s,
                changeId = changeId,
                cwd = cwd,
                binds = binds,
                envs = envs
            )
        )
    }

    DrawerExpandableSection(title = "Proot Options", defaultExpanded = false) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Applied on the next terminal spawn. Match proot-distro login semantics.",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it; persist() },
                label = { Text("Username (blank/root = root)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it; persist() },
                label = { Text("Hostname") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = kernel,
                onValueChange = { kernel = it; persist() },
                label = { Text("Kernel release (blank = default)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = changeId,
                onValueChange = { changeId = it; persist() },
                label = { Text("change-id override (e.g. 0:0 — blank = auto)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = cwd,
                onValueChange = { cwd = it; persist() },
                label = { Text("Working directory (blank = home)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors()
            )
            Spacer(Modifier.height(12.dp))

            SwitchRow("Shared home", sharedHome) { sharedHome = it; persist() }
            SwitchRow("Isolated (no /sdcard, /storage binds)", isolated) { isolated = it; persist() }
            SwitchRow("Minimal binds", minimal) { minimal = it; persist() }
            SwitchRow("Disable sysvipc", noSysvipc) { noSysvipc = it; persist() }
            SwitchRow("Disable link2symlink", noLink2s) { noLink2s = it; persist() }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = true,
                    onCheckedChange = {},
                    enabled = false,
                    colors = switchColors()
                )
                Spacer(Modifier.width(8.dp))
                Text("Shared /tmp (forced)", color = Color.White.copy(alpha = 0.7f))
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = bindsText,
                onValueChange = { bindsText = it; persist() },
                label = { Text("Custom binds (one per line, SRC:DST)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = envsText,
                onValueChange = { envsText = it; persist() },
                label = { Text("Custom env vars (one per line, KEY=VALUE)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                colors = fieldColors()
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onToggle, colors = switchColors())
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White)
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFFC3B6F9),
    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
    focusedLabelColor = Color(0xFFC3B6F9),
    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFFC3B6F9),
    checkedTrackColor = Color(0xFF7C3AED),
    disabledCheckedThumbColor = Color(0xFFC3B6F9).copy(alpha = 0.6f),
    disabledCheckedTrackColor = Color(0xFF7C3AED).copy(alpha = 0.4f)
)