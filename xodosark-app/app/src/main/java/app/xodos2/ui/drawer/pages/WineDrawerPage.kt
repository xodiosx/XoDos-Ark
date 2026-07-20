package app.xodos2.ui.drawer.pages

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xodos2.TerminalSessionIds
import app.xodos2.shell.ShellFonts
import app.xodos2.ui.dialog.MOUSE_MODE_TABLET
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.drawer.menu.DrawerMenu
import app.xodos2.ui.drawer.menu.DrawerMenuActions
import app.xodos2.ui.drawer.menu.DrawerMenuLabels
import app.xodos2.ui.drawer.menu.DrawerMenuOptions
import app.xodos2.ui.drawer.menu.DrawerScriptEditor
import app.xodos2.ui.glass.GlassButton
import app.xodos2.ui.prefs.AppPrefs
import app.xodos2.ui.runtime.TerminalSessionController
import app.xodos2.ui.runtime.NativeInstallCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun WineDrawerPage(
    wineX11ScriptEditorOpen: Boolean,
    onWineX11ScriptEditorOpenChange: (Boolean) -> Unit,
    onEnterWineX11Desktop: () -> Unit,
    prefs: SharedPreferences,
    drawerState: DrawerState,
    scope: CoroutineScope,
    terminalFontKey: String,
    terminalSessionState: TerminalSessionController.State,
    launcherDefault: String,
    desktopVulkanMode: String,
    desktopOpenGLMode: String,
    mouseMode: Int,
    resolutionPercent: Int,
    scalePercent: Int,
    waylandScriptEditorOpen: Boolean,
    onWaylandScriptEditorOpenChange: (Boolean) -> Unit,
    onEnterWineWaylandDesktop: () -> Unit,
    onEnterTerminal: () -> Unit,
    onLauncherDefaultSelect: (String) -> Unit,
    onDesktopVulkanSelect: (String) -> Unit,
    onDesktopOpenGLSelect: (String) -> Unit,
    onTerminalFontSelectLabel: (String) -> Unit,
    onTerminalSessionStateChange: (TerminalSessionController.State) -> Unit,
    onMouseModeSelectLabel: (String) -> Unit,
    onResolutionPercentSelectLabel: (String) -> Unit,
    onScalePercentSelectLabel: (String) -> Unit,
    vulkanOptions: List<String>,
    openGLOptions: List<String>,
    hasWineRootfs: Boolean = true,
    onContainerManagerClick: () -> Unit,
    onRequestKeyboard: () -> Unit = {},
    onExecuteCommand: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val activeContainerId = 3   // Container 3
    val containerDisplayName = remember(activeContainerId) {
        NativeInstallCoordinator.getContainerDisplayName(context, activeContainerId)
    }
    val distroId = remember(activeContainerId) {
        NativeInstallCoordinator.getContainerDistro(context, activeContainerId)
            ?: prefs.getString("container_distro_type_$activeContainerId", "linux") ?: "linux"
    }

    val desktopEnvNames = remember {
        listOf("XFCE Desktop", "LXQt Desktop", "KDE Plasma", "GNOME", "MATE", "Cinnamon")
    }
    var editingDeName by remember { mutableStateOf<String?>(null) }
    var deScriptText by remember { mutableStateOf("") }

    if (!hasWineRootfs) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Linux distro not installed.",
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

    val terminalFontLabel = remember(terminalFontKey) {
        ShellFonts.options.find { it.id == terminalFontKey }?.label
            ?: ShellFonts.options.firstOrNull()?.label
            ?: terminalFontKey
    }
    val terminalSessionLabel = remember(terminalSessionState.activeSessionId) {
        TerminalSessionIds.sessionPickerLine(terminalSessionState.activeSessionId, context)
    }
    val mouseModeLabel = remember(mouseMode) {
        if (mouseMode == MOUSE_MODE_TABLET) "Tablet" else "Touchpad"
    }
    val resolutionLabel = remember(resolutionPercent) { "${resolutionPercent.coerceIn(10, 100)}%" }
    val scaleLabel = remember(scalePercent) { "${scalePercent.coerceIn(100, 1000)}%" }
    val launcherMenuLabel = remember(launcherDefault) { AppPrefs.launcherPrefToMenuLabel(launcherDefault) }

    DrawerMenu(
        title = containerDisplayName,
        labels = DrawerMenuLabels(
            launcherDefaultLabel = launcherMenuLabel,
            desktopVulkanLabel = desktopVulkanMode,
            desktopOpenGLLabel = desktopOpenGLMode,
            terminalFontLabel = terminalFontLabel,
            terminalSessionLabel = terminalSessionLabel,
            mouseModeLabel = mouseModeLabel,
            resolutionPercentLabel = resolutionLabel,
            scalePercentLabel = scaleLabel,
        ),
        options = DrawerMenuOptions(
            launcherDefaultOptions = listOf(
                AppPrefs.LAUNCHER_MENU_WAYLAND,
                AppPrefs.LAUNCHER_MENU_TERMINAL,
                AppPrefs.LAUNCHER_MENU_X11,
            ),
            desktopVulkanOptions = vulkanOptions,
            desktopOpenGLOptions = openGLOptions,
            terminalFontOptions = ShellFonts.options.map { it.label },
            terminalSessionOptions = buildList {
                add(TerminalSessionIds.sessionPickerLine(TerminalSessionIds.FIRST_TERMINAL, context))
                terminalSessionState.sessionIds
                    .sorted()
                    .filter { it != TerminalSessionIds.FIRST_TERMINAL }
                    .forEach { add(TerminalSessionIds.sessionPickerLine(it, context)) }
                add("New session")
                add("Close current session")
            },
            mouseModeOptions = listOf("Touchpad", "Tablet"),
            resolutionPercentOptions = (10..100 step 10).map { "${it}%" },
            scalePercentOptions = (100..1000 step 100).map { "${it}%" },
        ),
        actions = DrawerMenuActions(
            onDesktopClick = {
                scope.launch { drawerState.close() }
                onEnterWineWaylandDesktop()
            },
            onDesktopLongPress = {
                onWaylandScriptEditorOpenChange(true)
            },
            onDebianDesktopClick = {
                scope.launch { drawerState.close() }
                onEnterWineX11Desktop()
            },
            onDebianDesktopLongPress = {
                onWineX11ScriptEditorOpenChange(true)
            },
            onTerminalClick = {
                scope.launch { drawerState.close() }
                onTerminalSessionStateChange(terminalSessionState.copy(activeSessionId = TerminalSessionIds.WINE_TERMINAL))
                onEnterTerminal()
            },
            onViewClick = { scope.launch { drawerState.close() } },
            onAppearanceClick = { scope.launch { drawerState.close() } },
            onSessionClick = { scope.launch { drawerState.close() } },
            onKeyboardClick = {
                onRequestKeyboard()
                scope.launch { drawerState.close() }
            },
            onLauncherDefaultSelect = onLauncherDefaultSelect,
            onDesktopVulkanSelect = onDesktopVulkanSelect,
            onDesktopOpenGLSelect = onDesktopOpenGLSelect,
            onTerminalFontSelect = onTerminalFontSelectLabel,
            onTerminalSessionSelect = { label ->
                val next = when (label) {
                    "New session" ->
                        TerminalSessionController.addNewInteractiveSession(terminalSessionState, TerminalSessionIds.NS_WINE)
                    "Close current session" ->
                        TerminalSessionController.closeCurrentSession(terminalSessionState)
                    else ->
                        TerminalSessionController.selectFromPickerLine(terminalSessionState, label)
                }
                onTerminalSessionStateChange(next)
            },
            onMouseModeSelect = onMouseModeSelectLabel,
            onResolutionPercentSelect = onResolutionPercentSelectLabel,
            onScalePercentSelect = onScalePercentSelectLabel,
            onCloseDrawerRequest = { scope.launch { drawerState.close() } },
        ),
        showDebianDesktop = true,
        extraContent = {
            WineExtraContent(
                prefs = prefs,
                waylandScriptEditorOpen = waylandScriptEditorOpen,
                onWaylandScriptEditorOpenChange = onWaylandScriptEditorOpenChange,
                hasWineRootfs = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            DrawerExpandableSection(title = "X11 Scripts", defaultExpanded = false) {
                if (wineX11ScriptEditorOpen) {
                    DrawerScriptEditor(
                        title = "X11 startup script",
                        initialText = AppPrefs.readWineDesktopStartupScript(prefs),
                        onSave = {
                            AppPrefs.writeWineDesktopStartupScript(prefs, it)
                            onWineX11ScriptEditorOpenChange(false)
                        },
                    )
                } else {
                    Text(
                        text = "Edit X11 startup script",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onWineX11ScriptEditorOpenChange(true) }
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                    )
                }
            }
DesktopLaunchersSection(
        containerId = 3,                    
        prefs = prefs,
        onExecuteCommand = onExecuteCommand
    )

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
                                contentDescription = "Edit Startup Script",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            CommandsSection(
                prefs = prefs,
                onExecuteCommand = onExecuteCommand
            )
            Spacer(modifier = Modifier.height(8.dp))
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
                    .padding(vertical = 12.dp, horizontal = 12.dp)
            )
        }
    )

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
                GlassButton(
                    onClick = {
                        prefs.edit().putString(prefKey, deScriptText.trim()).apply()
                        editingDeName = null
                    }
                ) {
                    Text("Save", color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(
                        onClick = {
                            deScriptText = DesktopInstallScripts.buildDesktopInstallScript(distroId, targetDe)
                        }
                    ) {
                        Text("Reset", color = Color(0xFFFF6B6B))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    GlassButton(onClick = { editingDeName = null }) {
                        Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }
        )
    }
}

@Composable
private fun WineExtraContent(
    prefs: SharedPreferences,
    waylandScriptEditorOpen: Boolean,
    onWaylandScriptEditorOpenChange: (Boolean) -> Unit,
    hasWineRootfs: Boolean
) {
    DrawerExpandableSection(title = "Wayland scripts", defaultExpanded = false) {
        if (waylandScriptEditorOpen) {
            DrawerScriptEditor(
                title = "Wayland desktop startup script",
                initialText = prefs.getString("wdesktop_startup_script", "") ?: "",
                onSave = {
                    prefs.edit().putString("wdesktop_startup_script", it).apply()
                    onWaylandScriptEditorOpenChange(false)
                },
            )
        } else {
            Text(
                text = "Edit Wayland startup script",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onWaylandScriptEditorOpenChange(true) }
                    .padding(vertical = 12.dp, horizontal = 12.dp),
            )
        }
    }
}