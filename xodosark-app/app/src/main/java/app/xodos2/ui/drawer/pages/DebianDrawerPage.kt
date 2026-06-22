package app.xodos2.ui.drawer.pages

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xodos2.TerminalSessionIds
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.drawer.menu.DrawerScriptEditor
import app.xodos2.ui.prefs.AppPrefs
import app.xodos2.ui.runtime.TerminalSessionController
import app.xodos2.ui.runtime.NativeInstallCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun DebianDrawerPage(
    prefs: SharedPreferences,
    drawerState: DrawerState,
    scope: CoroutineScope,
    terminalSessionState: TerminalSessionController.State,
    onTerminalSessionStateChange: (TerminalSessionController.State) -> Unit,
    x11ScriptEditorOpen: Boolean,
    onX11ScriptEditorOpenChange: (Boolean) -> Unit,
    onEnterDebianDesktop: () -> Unit,
    onEnterTerminal: () -> Unit,
    onExitDisplayModes: () -> Unit,
    hasDebianRootfs: Boolean = true,
    onContainerManagerClick: () -> Unit,
    onOpenX11Settings: () -> Unit = {},
    onExecuteCommand: (String) -> Unit = {}    // NEW
) {
    val context = LocalContext.current

    // --- Container / distro info (container 2) ---
    val distroId = remember {
        NativeInstallCoordinator.getContainerDistro(context, 2)
            ?: prefs.getString("container_distro_type_2", "linux") ?: "linux"
    }

    // --- DE install state ---
    val desktopEnvNames = remember {
        listOf("XFCE Desktop", "LXQt Desktop", "KDE Plasma", "GNOME", "MATE", "Cinnamon")
    }
    var editingDeName by remember { mutableStateOf<String?>(null) }
    var deScriptText by remember { mutableStateOf("") }

    if (!hasDebianRootfs) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Distro not installed.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Container Manager",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        scope.launch { drawerState.close() }
                        onContainerManagerClick()
                    }
                    .padding(vertical = 12.dp)
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = NativeInstallCoordinator.getContainerDisplayName(context, 2),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))

        // X11 Desktop button
        Text(
            text = "X11",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onX11ScriptEditorOpenChange(true) },
                        onTap = {
                            scope.launch {
                                drawerState.close()
                                onEnterDebianDesktop()
                            }
                        }
                    )
                }
                .padding(vertical = 12.dp)
        )

        // Scripts (existing)
        DrawerExpandableSection(title = "X11 Scripts", defaultExpanded = false) {
            if (x11ScriptEditorOpen) {
                DrawerScriptEditor(
                    title = "X11 startup script",
                    initialText = AppPrefs.readDebianDesktopStartupScript(prefs),
                    onSave = {
                        AppPrefs.writeDebianDesktopStartupScript(prefs, it)
                        onX11ScriptEditorOpenChange(false)
                    }
                )
            } else {
                Text(
                    text = "Edit X11 startup script",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onX11ScriptEditorOpenChange(true) }
                        .padding(vertical = 12.dp, horizontal = 12.dp)
                )
            }
        }

        // ------------------ NEW: Install Desktop ------------------
        DrawerExpandableSection(title = "Install Desktop", defaultExpanded = false) {
            desktopEnvNames.forEach { name ->
                val prefKey = "custom_install_script_${distroId}_${name.replace(" ", "_")}"

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                scope.launch { drawerState.close() }
                                val script = prefs.getString(prefKey, null)
                                    ?: DesktopInstallScripts.buildDesktopInstallScript(distroId, name)
                                onExecuteCommand(script)
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                    )
                    IconButton(
                        onClick = {
                            editingDeName = name
                            deScriptText = prefs.getString(prefKey, null)
                                ?: DesktopInstallScripts.buildDesktopInstallScript(distroId, name)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Install Script",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        // -----------------------------------------------------------

        // Terminal button
        Text(
            text = "Terminal",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    scope.launch { drawerState.close() }
                    onTerminalSessionStateChange(
                        terminalSessionState.copy(activeSessionId = TerminalSessionIds.DEBIAN_TERMINAL)
                    )
                    onExitDisplayModes()
                    onEnterTerminal()
                }
                .padding(vertical = 12.dp)
        )

        // X11 Settings button
        Text(
            text = "X11 Settings",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    scope.launch { drawerState.close() }
                    onOpenX11Settings()
                }
                .padding(vertical = 12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Container Manager
        Text(
            text = "Container Manager",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    scope.launch { drawerState.close() }
                    onContainerManagerClick()
                }
                .padding(vertical = 12.dp)
        )
    }

    // --- Script editing dialog ---
    if (editingDeName != null) {
        val targetDe = editingDeName!!
        val prefKey = "custom_install_script_${distroId}_${targetDe.replace(" ", "_")}"

        AlertDialog(
            onDismissRequest = { editingDeName = null },
            title = { Text("Edit Setup Script: $targetDe") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = deScriptText,
                        onValueChange = { deScriptText = it },
                        label = { Text("Installation Sequence (.sh)") },
                        singleLine = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putString(prefKey, deScriptText.trim()).apply()
                        editingDeName = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            deScriptText = DesktopInstallScripts.buildDesktopInstallScript(distroId, targetDe)
                        }
                    ) { Text("Reset", color = MaterialTheme.colorScheme.error) }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { editingDeName = null }) { Text("Cancel") }
                }
            }
        )
    }
}