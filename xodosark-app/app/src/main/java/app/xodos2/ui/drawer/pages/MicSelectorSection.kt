package app.xodos2.ui.drawer.pages

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.MicManager
import kotlinx.coroutines.launch

@Composable
fun MicSelectorSection(
    onRestartRequested: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(MicManager.isEnabled(context)) }
    var showMixer by remember { mutableStateOf(false) }
    var restartReason by remember { mutableStateOf<String?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    var pendingEnable by remember { mutableStateOf(false) }

    val requestPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toastMsg = "Microphone permission denied"
            return@rememberLauncherForActivityResult
        }
        // Got permission — try hot-apply first
        scope.launch {
            MicManager.setEnabled(context, pendingEnable)
            val hot = MicManager.hotApply(pendingEnable)
            enabled = pendingEnable
            if (hot) {
                toastMsg = if (pendingEnable) "Mic enabled (live)" else "Mic disabled (live)"
            } else {
                restartReason = if (pendingEnable)
                    "Microphone source added. Restart to let PulseAudio load the input module."
                else
                    "Microphone source removed. Restart to unload the input module."
            }
        }
    }

    fun applyToggle(wantOn: Boolean) {
        pendingEnable = wantOn
        if (wantOn) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPerm.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        scope.launch {
            MicManager.setEnabled(context, wantOn)
            val hot = MicManager.hotApply(wantOn)
            enabled = wantOn
            if (hot) {
                toastMsg = if (wantOn) "Mic enabled (live)" else "Mic disabled (live)"
            } else {
                restartReason = if (wantOn)
                    "Microphone source added. Restart to let PulseAudio load the input module."
                else
                    "Microphone source removed. Restart to unload the input module."
            }
        }
    }

    DrawerExpandableSection(title = "Microphone", defaultExpanded = false) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable microphone input",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (enabled)
                        "PulseAudio captures from the device mic"
                    else
                        "No app can record audio",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(checked = enabled, onCheckedChange = { applyToggle(it) })
        }

        if (enabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = { showMixer = true }) {
                    Text("Open Mic Monitor")
                }
            }
        }

        toastMsg?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }

    // ── Restart dialog ──
    restartReason?.let { reason ->
        AlertDialog(
            onDismissRequest = { restartReason = null },
            title = { Text("Restart required") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = {
                    restartReason = null
                    onRestartRequested()
                }) { Text("Restart Now") }
            },
            dismissButton = {
                TextButton(onClick = { restartReason = null }) { Text("Later") }
            }
        )
    }

    // ── Mixer dialog ──
    if (showMixer) {
        MicMixerDialog(onClose = { showMixer = false })
    }
}

@Composable
private fun MicMixerDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("Checking…") }

    LaunchedEffect(Unit) {
        val name = MicManager.getSourceName(context)
        if (name == null) {
            status = "Not loaded — restart the app to pick up the mic source"
            return@LaunchedEffect
        }
        status = "Live · $name"
        MicManager.pinDefaultSource()
        MicManager.levelFlow(name).collect { level = it }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Microphone Monitor") },
        text = {
            Column {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(Color(0xFF1F1F1F), RoundedCornerShape(6.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(level.coerceIn(0f, 1f))
                            .background(
                                color = when {
                                    level > 0.8f -> Color(0xFFFF5252)
                                    level > 0.4f -> Color(0xFFFFC107)
                                    else         -> Color(0xFF4CAF50)
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Speak into the mic — the bar should move",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        }
    )
}