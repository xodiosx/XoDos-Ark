package app.xodos2.ui.drawer.pages

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.xodos2.R
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

    // Real toast so it's visible even if the drawer closes
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    val requestPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toastMsg = context.getString(R.string.app_micsel_toast_denied)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            MicManager.setEnabled(context, pendingEnable)
            val hot = MicManager.hotApply(pendingEnable)
            enabled = pendingEnable
            if (hot) {
                toastMsg = context.getString(
                    if (pendingEnable) R.string.app_micsel_toast_enabled
                    else               R.string.app_micsel_toast_disabled
                )
            } else {
                restartReason = context.getString(
                    if (pendingEnable) R.string.app_micsel_restart_enable_reason
                    else               R.string.app_micsel_restart_disable_reason
                )
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
                toastMsg = context.getString(
                    if (wantOn) R.string.app_micsel_toast_enabled
                    else        R.string.app_micsel_toast_disabled
                )
            } else {
                restartReason = context.getString(
                    if (wantOn) R.string.app_micsel_restart_enable_reason
                    else        R.string.app_micsel_restart_disable_reason
                )
            }
        }
    }

    DrawerExpandableSection(
        title = stringResource(R.string.app_micsel_title),
        defaultExpanded = false
    ) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_micsel_toggle_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(
                        if (enabled) R.string.app_micsel_state_on
                        else         R.string.app_micsel_state_off
                    ),
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
                    Text(stringResource(R.string.app_micsel_open_monitor))
                }
            }
        }
    }

    // ── Restart dialog ──
    restartReason?.let { reason ->
        AlertDialog(
            onDismissRequest = { restartReason = null },
            title = { Text(stringResource(R.string.app_micsel_restart_title)) },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = {
                    restartReason = null
                    onRestartRequested()
                }) { Text(stringResource(R.string.app_micsel_restart_now)) }
            },
            dismissButton = {
                TextButton(onClick = { restartReason = null }) {
                    Text(stringResource(R.string.app_micsel_restart_later))
                }
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
    var level by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        status = stringResourceFallback(R.string.app_micsel_monitor_checking)
        val name = MicManager.getSourceName()
        if (name == null) {
            status = stringResourceFallback(R.string.app_micsel_monitor_not_loaded)
            return@LaunchedEffect
        }
        status = stringResourceFallback(R.string.app_micsel_monitor_live, name)
        MicManager.pinDefaultSource()
        MicManager.levelFlow(name).collect { level = it }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.app_micsel_monitor_title)) },
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
                    text = stringResource(R.string.app_micsel_monitor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.app_micsel_close))
            }
        }
    )
}

/** Small helper — LocalContext-backed `getString`, usable inside non-composable coroutines. */
@Composable
private fun stringResourceFallback(id: Int, vararg args: Any): String {
    val ctx = LocalContext.current
    return remember(id, args) { ctx.getString(id, *args) }
}