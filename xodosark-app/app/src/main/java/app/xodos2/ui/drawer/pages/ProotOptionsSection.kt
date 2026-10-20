package app.xodos2.ui.drawer.pages

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.NativeInstallCoordinator

@Composable
fun ProotOptionsSection(
    prefs: SharedPreferences,
    containerId: Int,
) {
    val context = LocalContext.current

    // Prefer JSON (authoritative for the Rust layer), fall back to prefs on first run
    val initial = remember(containerId) {
        NativeInstallCoordinator.loadProotOptions(context, containerId)
    }

    var username by remember { mutableStateOf(initial.username.ifBlank { "root" }) }
    var hostname by remember { mutableStateOf(initial.hostname.ifBlank { "localhost" }) }
    var sharedHome by remember { mutableStateOf(initial.sharedHome) }
    var kernelRelease by remember { mutableStateOf(initial.kernelRelease) }

    fun persist() {
        // Mirror into prefs for UI-only consumers
        prefs.edit()
            .putString("proot_username_$containerId", username)
            .putString("proot_hostname_$containerId", hostname)
            .putBoolean("proot_shared_home_$containerId", sharedHome)
            .putString("proot_kernel_release_$containerId", kernelRelease)
            .apply()

        // Write the JSON file that proot.rs reads on the next session spawn
        NativeInstallCoordinator.saveProotOptions(
            context = context,
            containerId = containerId,
            opts = NativeInstallCoordinator.ProotOptions(
                username = username,
                hostname = hostname,
                sharedHome = sharedHome,
                sharedTmp = true,
                kernelRelease = kernelRelease
            )
        )
    }

    DrawerExpandableSection(title = "Proot Options", defaultExpanded = false) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

            Text(
                "Login shell settings — applied the next time a terminal for this container is started.",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it; persist() },
                label = { Text("Username (default: root)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFC3B6F9),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedLabelColor = Color(0xFFC3B6F9),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it; persist() },
                label = { Text("Hostname (default: localhost)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFC3B6F9),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedLabelColor = Color(0xFFC3B6F9),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = kernelRelease,
                onValueChange = { kernelRelease = it; persist() },
                label = { Text("Kernel release (blank = default)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFC3B6F9),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedLabelColor = Color(0xFFC3B6F9),
                    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = sharedHome,
                    onCheckedChange = { sharedHome = it; persist() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFFC3B6F9),
                        checkedTrackColor = Color(0xFF7C3AED)
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text("Shared home folder", color = Color.White)
            }
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = true,
                    onCheckedChange = { /* forced */ },
                    enabled = false,
                    colors = SwitchDefaults.colors(
                        disabledCheckedThumbColor = Color(0xFFC3B6F9).copy(alpha = 0.6f),
                        disabledCheckedTrackColor = Color(0xFF7C3AED).copy(alpha = 0.4f)
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text("Shared /tmp (forced)", color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}