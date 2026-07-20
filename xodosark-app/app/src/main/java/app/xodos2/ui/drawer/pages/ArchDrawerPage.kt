package app.xodos2.ui.drawer.pages

import android.content.SharedPreferences
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xodos2.ui.glassDialogStyle
import app.xodos2.ui.glass.GlassButton
import app.xodos2.TerminalSessionIds
import app.xodos2.shell.ShellFonts
import app.xodos2.ui.dialog.MOUSE_MODE_TABLET
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.drawer.menu.DrawerMenu
import app.xodos2.ui.drawer.menu.DrawerMenuActions
import app.xodos2.ui.drawer.menu.DrawerMenuLabels
import app.xodos2.ui.drawer.menu.DrawerMenuOptions
import app.xodos2.ui.drawer.menu.DrawerScriptEditor
import app.xodos2.ui.prefs.AppPrefs
import app.xodos2.ui.runtime.TerminalSessionController
import app.xodos2.ui.runtime.NativeInstallCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

// ----------------------------------------------------------------
// Data class for saved commands with title is now in CommandsModels.kt
// ----------------------------------------------------------------

// ----------------------------------------------------------------
// ArchDrawerPage composable
// ----------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchDrawerPage(
    archX11ScriptEditorOpen: Boolean,
    onArchX11ScriptEditorOpenChange: (Boolean) -> Unit,
    onEnterArchX11Desktop: () -> Unit,
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
    onEnterWaylandDesktop: () -> Unit,
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
    hasArchRootfs: Boolean = true,
    onContainerManagerClick: () -> Unit,
    onRequestKeyboard: () -> Unit = {},
    onExecuteCommand: (String) -> Unit = {}
) {
    val context = LocalContext.current

    // Dynamically derive the active container ID based on the current terminal session.
    // This updates the UI and scripts immediately when the user switches terminal tabs.
    val activeContainerId = 1   // this drawer is always Container 1
    val containerDisplayName = remember(activeContainerId) {
        NativeInstallCoordinator.getContainerDisplayName(context, activeContainerId)
    }

    val distroId = remember(activeContainerId) {
        NativeInstallCoordinator.getContainerDistro(context, activeContainerId)
            ?: prefs.getString("container_distro_type_$activeContainerId", "linux")
            ?: "linux"
    }

    // ===================== Commands state =====================
    var showCommandsDialog by remember { mutableStateOf(false) }
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf<Int?>(null) }
    val savedCommands = remember { mutableStateListOf<SavedCommand>() }

    // ===================== DE Script Editor State =====================
    var editingDeName by remember { mutableStateOf<String?>(null) }
    var deScriptText by remember { mutableStateOf("") }

    LaunchedEffect(showCommandsDialog) {
        if (showCommandsDialog) {
            savedCommands.clear()
            val rawStrings = AppPrefs.loadCommands(prefs)
            rawStrings.forEach { json ->
                try {
                    val obj = JSONObject(json)
                    savedCommands.add(
                        SavedCommand(
                            title = obj.optString("title", ""),
                            command = obj.optString("command", "")
                        )
                    )
                } catch (_: Exception) {
                    savedCommands.add(SavedCommand(title = "", command = json))
                }
            }
        }
    }

    fun persistCommands() {
        val jsonList = savedCommands.map { cmd ->
            JSONObject().apply {
                put("title", cmd.title)
                put("command", cmd.command)
            }.toString()
        }
        AppPrefs.saveCommands(prefs, jsonList)
    }

    // ===================== Desktop environment list =====================
    val desktopEnvNames = remember {
        listOf("XFCE Desktop", "LXQt Desktop", "KDE Plasma", "GNOME", "MATE", "Cinnamon")
    }

    // ===================== Existing UI =====================
    if (!hasArchRootfs) {
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
                onEnterWaylandDesktop()
            },
            onDesktopLongPress = {
                onWaylandScriptEditorOpenChange(true)
            },
            onDebianDesktopClick = {
                scope.launch { drawerState.close() }
                onEnterArchX11Desktop()
            },
            onDebianDesktopLongPress = {
                onArchX11ScriptEditorOpenChange(true)
            },
            onTerminalClick = {
                scope.launch { drawerState.close() }
                onTerminalSessionStateChange(terminalSessionState.copy(activeSessionId = TerminalSessionIds.ARCH_TERMINAL))
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
                        TerminalSessionController.addNewInteractiveSession(terminalSessionState, TerminalSessionIds.NS_ARCH)
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
            ArchExtraContent(
                prefs = prefs,
                waylandScriptEditorOpen = waylandScriptEditorOpen,
                onWaylandScriptEditorOpenChange = onWaylandScriptEditorOpenChange,
                hasArchRootfs = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            DrawerExpandableSection(title = "X11 Scripts", defaultExpanded = false) {
                if (archX11ScriptEditorOpen) {
                    DrawerScriptEditor(
                        title = "X11 startup script",
                        initialText = AppPrefs.readArchX11DesktopStartupScript(prefs),
                        onSave = {
                            AppPrefs.writeArchX11DesktopStartupScript(prefs, it)
                            onArchX11ScriptEditorOpenChange(false)
                        },
                    )
                } else {
                    Text(
                        text = "Edit X11 startup script",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onArchX11ScriptEditorOpenChange(true) }
                            .padding(vertical = 12.dp, horizontal = 12.dp)
                    )
                }
            }


DesktopLaunchersSection(
        containerId = 1,                     // or 2 for Debian, 3 for Wine
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
                                contentDescription = "Edit Startup Script Package Setup",
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Commands",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCommandsDialog = true }
                    .padding(vertical = 12.dp, horizontal = 12.dp)
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

    // ===================== Desktop Environment Script Editor Dialog =====================
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

    // ===================== Commands dialogs =====================
    if (showCommandsDialog) {
        AlertDialog(
            onDismissRequest = { showCommandsDialog = false },
            containerColor = Color.Transparent,
            modifier = Modifier.glassDialogStyle(),
            title = { Text("Saved Commands", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column {
                    GlassButton(
                        onClick = {
                            editingIndex = null
                            editTitle = ""
                            editText = ""
                            showAddEditDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFC3B6F9))
                        Spacer(Modifier.width(4.dp))
                        Text("Add command", color = Color(0xFFC3B6F9))
                    }
                    Spacer(Modifier.height(8.dp))

                    if (savedCommands.isEmpty()) {
                        Text(
                            "No saved commands.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    } else {
                        LazyColumn {
                            items(items = savedCommands, key = { it.command }) { cmd ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                onExecuteCommand(cmd.command)
                                                showCommandsDialog = false
                                            },
                                            onLongClick = {
                                                showDeleteConfirm = savedCommands.indexOf(cmd)
                                            }
                                        )
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = cmd.title.ifBlank { cmd.command },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White
                                    )
                                    IconButton(onClick = {
                                        editingIndex = savedCommands.indexOf(cmd)
                                        editTitle = cmd.title
                                        editText = cmd.command
                                        showAddEditDialog = true
                                    }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            modifier = Modifier.size(20.dp),
                                            tint = Color.White.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                GlassButton(onClick = { showCommandsDialog = false }) {
                    Text("Close", color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showAddEditDialog) {
        AlertDialog(
            onDismissRequest = { showAddEditDialog = false },
            containerColor = Color.Transparent,
            modifier = Modifier.glassDialogStyle(),
            title = { Text(if (editingIndex == null) "Add command" else "Edit command", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
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
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        label = { Text("Shell command") },
                        singleLine = false,
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
                }
            },
            confirmButton = {
                GlassButton(onClick = {
                    val title = editTitle.trim()
                    val command = editText.trim()
                    if (command.isNotEmpty()) {
                        if (editingIndex != null) {
                            savedCommands[editingIndex!!] = SavedCommand(title, command)
                        } else {
                            savedCommands.add(SavedCommand(title, command))
                        }
                        persistCommands()
                    }
                    showAddEditDialog = false
                }) { Text("Save", color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                GlassButton(onClick = { showAddEditDialog = false }) { Text("Cancel", color = Color.White.copy(alpha = 0.8f)) }
            }
        )
    }

    if (showDeleteConfirm != null) {
        val idx = showDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = Color.Transparent,
            modifier = Modifier.glassDialogStyle(),
            title = { Text("Delete command?", fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text("Remove \"${savedCommands[idx].title.ifBlank { savedCommands[idx].command }}\"?", color = Color.White.copy(alpha = 0.85f)) },
            confirmButton = {
                GlassButton(onClick = {
                    savedCommands.removeAt(idx)
                    persistCommands()
                    showDeleteConfirm = null
                }) { Text("Delete", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                GlassButton(onClick = { showDeleteConfirm = null }) { Text("Cancel", color = Color.White.copy(alpha = 0.8f)) }
            }
        )
    }
}

@Composable
private fun ArchExtraContent(
    prefs: SharedPreferences,
    waylandScriptEditorOpen: Boolean,
    onWaylandScriptEditorOpenChange: (Boolean) -> Unit,
    hasArchRootfs: Boolean
) {
    DrawerExpandableSection(title = "Wayland scripts", defaultExpanded = false) {
        if (waylandScriptEditorOpen) {
            DrawerScriptEditor(
                title = "Wayland desktop startup script",
                initialText = prefs.getString("desktop_startup_script", "") ?: "",
                onSave = {
                    prefs.edit().putString("desktop_startup_script", it).apply()
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