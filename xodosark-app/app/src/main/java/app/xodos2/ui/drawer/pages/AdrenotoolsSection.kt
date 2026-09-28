package app.xodos2.ui.drawer.pages

import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xodos2.ui.glass.GlassButton
import app.xodos2.ui.glassDialogStyle
import app.xodos2.ui.runtime.AdrenotoolsDriverManager
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AdrenotoolsDrawerButton(
    prefs: SharedPreferences,
    onExecuteCommand: (String) -> Unit,
    snackbarHostState: SnackbarHostState? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }

    Text(
        text = "Adrenotools",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val bashrc = File(context.filesDir, "usr/etc/bash.bashrc")
                if (bashrc.isFile) {
                    showDialog = true
                } else if (snackbarHostState != null) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Please install extra native archive first!",
                            withDismissAction = true,
                            duration = SnackbarDuration.Short
                        )
                    }
                }
            }
            .padding(vertical = 12.dp, horizontal = 12.dp)
    )

    if (showDialog) {
        AdrenotoolsDialog(
            prefs = prefs,
            onDismiss = { showDialog = false },
            onExecuteCommand = onExecuteCommand
        )
    }
}

@Composable
private fun AdrenotoolsDialog(
    prefs: SharedPreferences,
    onDismiss: () -> Unit,
    onExecuteCommand: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var drivers by remember { mutableStateOf(AdrenotoolsDriverManager.refreshAndList(context)) }
    var activeName by remember { mutableStateOf(AdrenotoolsDriverManager.activeName(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<AdrenotoolsDriverManager.InstalledDriver?>(null) }

    fun reload() {
        drivers = AdrenotoolsDriverManager.refreshAndList(context)
        activeName = AdrenotoolsDriverManager.activeName(context)
    }

    /** Sources the freshly-written drv file in the current terminal session. */
    fun sourceDrvInTerminal() {
        onExecuteCommand("source ${AdrenotoolsDriverManager.DRV_PATH_IN_CONTAINER}")
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "Installing…"
            val res = AdrenotoolsDriverManager.installFromUri(context, uri) { _, msg -> status = msg }
            busy = false
            status = res.fold(
                onSuccess = { "Installed ${it.meta.name}" },
                onFailure = { "Install failed: ${it.message}" }
            )
            reload()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = {
            Text("Adrenotools GPU Drivers", fontWeight = FontWeight.Bold, color = Color.White)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {

                GlassButton(onClick = {
                    picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp), tint = Color(0xFFC3B6F9))
                    Spacer(Modifier.width(4.dp))
                    Text("Add driver ZIP", color = Color(0xFFC3B6F9))
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {

                    // ── System driver row ──
                    item {
                        DriverRow(
                            title = "Android system driver",
                            subtitle = "Uses wrapper settings",
                            isSystem = true,
                            isActive = activeName == null,
                            enabled = !busy,
                            onActivate = {
                                scope.launch {
                                    busy = true
                                    status = "Activating system driver…"
                                    val ok = AdrenotoolsDriverManager.activateSystem(context, prefs)
                                    busy = false
                                    status = if (ok) "System driver activated" else "Failed"
                                    reload()
                                    if (ok) sourceDrvInTerminal()
                                }
                            },
                            onDelete = null
                        )
                        Divider(color = Color.White.copy(alpha = 0.1f))
                    }

                    if (drivers.isEmpty()) {
                        item {
                            Text(
                                text = "No custom drivers installed.",
                                color = Color.White.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                            )
                        }
                    } else {
                        items(items = drivers, key = { it.meta.name }) { entry ->
                            DriverRow(
                                title = entry.meta.name,
                                subtitle = buildString {
                                    if (entry.meta.driverVersion.isNotBlank())
                                        append(entry.meta.driverVersion)
                                    if (entry.meta.author.isNotBlank()) {
                                        if (isNotEmpty()) append(" • ")
                                        append(entry.meta.author)
                                    }
                                    if (entry.meta.libraryName.isNotBlank()) {
                                        if (isNotEmpty()) append(" • ")
                                        append(entry.meta.libraryName)
                                    }
                                },
                                isSystem = false,
                                isActive = activeName == entry.meta.name,
                                enabled = !busy,
                                onActivate = {
                                    scope.launch {
                                        busy = true
                                        status = "Activating ${entry.meta.name}…"
                                        val res = AdrenotoolsDriverManager.activate(
                                            context = context,
                                            prefs = prefs,
                                            name = entry.meta.name,
                                            onProgress = { _, msg -> status = msg }
                                        )
                                        busy = false
                                        status = res.fold(
                                            onSuccess = { "Activated ${entry.meta.name}" },
                                            onFailure = { "Failed: ${it.message}" }
                                        )
                                        reload()
                                        if (res.isSuccess) sourceDrvInTerminal()
                                    }
                                },
                                onDelete = { pendingDelete = entry }
                            )
                        }
                    }
                }

                status?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = { if (!busy) onDismiss() }) {
                Text("Close", color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold)
            }
        }
    )

    // ── Delete confirm ──
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Color.Transparent,
            modifier = Modifier.glassDialogStyle(),
            title = {
                Text("Delete ${entry.meta.name}?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "The ZIP and its entry will be removed.",
                    color = Color.White.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                GlassButton(onClick = {
                    val wasActive = AdrenotoolsDriverManager.activeName(context) == entry.meta.name
                    AdrenotoolsDriverManager.uninstall(context, prefs, entry.meta.name)
                    pendingDelete = null
                    reload()
                    // Manager already rewrote the drv file if it had to fall back
                    // to system — just source it into the current terminal.
                    if (wasActive) sourceDrvInTerminal()
                }) {
                    Text("Delete", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                GlassButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                }
            }
        )
    }
}

@Composable
private fun DriverRow(
    title: String,
    subtitle: String,
    isSystem: Boolean,
    isActive: Boolean,
    enabled: Boolean,
    onActivate: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isSystem) Icons.Default.PhoneAndroid else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = if (isActive) Color(0xFFC3B6F9) else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isActive) Color(0xFFC3B6F9) else Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (isActive) {
            Text(
                text = "Active",
                color = Color(0xFFC3B6F9),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        } else {
            IconButton(onClick = onActivate, enabled = enabled) {
                Icon(Icons.Default.PlayArrow, "Activate", tint = Color(0xFFC3B6F9))
            }
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF6B6B))
            }
        }
    }
}