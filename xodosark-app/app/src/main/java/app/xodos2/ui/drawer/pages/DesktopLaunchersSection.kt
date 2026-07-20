// DesktopLaunchersSection.kt
package app.xodos2.ui.drawer.pages

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.glass.GlassButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DesktopLaunchersSection(
    containerId: Int,
    prefs: SharedPreferences,
    onExecuteCommand: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var scanResult by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var showManualPick by remember { mutableStateOf(false) }
    var editingBinary by remember { mutableStateOf<String?>(null) }
    var editScript by remember { mutableStateOf("") }

    DrawerExpandableSection(title = "Desktop Launchers", defaultExpanded = false) {
        // Scan button
        Button(
            onClick = {
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        DesktopDetector.detectInstalled(context, containerId)
                    }
                    scanResult = result
                    showManualPick = result.isEmpty()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Scan for installed desktops")
        }

        Spacer(Modifier.height(8.dp))

        // Detected buttons
        scanResult?.let { detected ->
            if (detected.isNotEmpty()) {
                Column {
                    detected.forEach { (displayName, binary) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        val script = getCustomScript(prefs, containerId, binary)
                                            ?: DesktopDetector.defaultLaunchScript(binary)
                                        onExecuteCommand(script)
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = {
                                    editingBinary = binary
                                    editScript = getCustomScript(prefs, containerId, binary)
                                        ?: DesktopDetector.defaultLaunchScript(binary)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    "Edit startup script",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // Manual dropdown fallback
        if (showManualPick) {
            var manualDropdownExpanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { manualDropdownExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select desktop environment to launch")
                }
                DropdownMenu(
                    expanded = manualDropdownExpanded,
                    onDismissRequest = { manualDropdownExpanded = false }
                ) {
                    DesktopDetector.knownBinaries.forEach { (binary, name) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                manualDropdownExpanded = false
                                val script = getCustomScript(prefs, containerId, binary)
                                    ?: DesktopDetector.defaultLaunchScript(binary)
                                onExecuteCommand(script)
                            }
                        )
                    }
                }
            }
        }
    }

    // Edit dialog with Reset functionality
    if (editingBinary != null) {
        val binary = editingBinary!!
        val displayName = DesktopDetector.knownBinaries[binary] ?: binary

        AlertDialog(
            onDismissRequest = { editingBinary = null },
            title = { Text("Edit startup script: $displayName") },
            text = {
                OutlinedTextField(
                    value = editScript,
                    onValueChange = { editScript = it },
                    label = { Text("Shell script") },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp)
                )
            },
            confirmButton = {
                GlassButton(onClick = {
                    saveCustomScript(prefs, containerId, binary, editScript.trim())
                    editingBinary = null
                }) {
                    Text("Save", color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Reset button – restore the default script
                    GlassButton(onClick = {
                        editScript = DesktopDetector.defaultLaunchScript(binary)
                        // Optionally delete the saved custom script so the default becomes permanent
                        deleteCustomScript(prefs, containerId, binary)
                    }) {
                        Text("Reset", color = Color(0xFFFF6B6B))
                    }
                    Spacer(Modifier.width(8.dp))
                    GlassButton(onClick = { editingBinary = null }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        )
    }
}

// Persistence helpers
private fun scriptPrefKey(containerId: Int, binary: String) =
    "desktop_launch_script_${containerId}_$binary"

private fun getCustomScript(prefs: SharedPreferences, containerId: Int, binary: String): String? {
    return prefs.getString(scriptPrefKey(containerId, binary), null)
}

private fun saveCustomScript(prefs: SharedPreferences, containerId: Int, binary: String, script: String) {
    prefs.edit().putString(scriptPrefKey(containerId, binary), script).apply()
}

private fun deleteCustomScript(prefs: SharedPreferences, containerId: Int, binary: String) {
    prefs.edit().remove(scriptPrefKey(containerId, binary)).apply()
}