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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.xodos2.R
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.MicManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun MicSelectorSection(
    drawerState: DrawerState,
    onRestartRequested: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED)
    }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE  -> isResumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var enabled by remember { mutableStateOf(MicManager.isEnabled(context)) }
    var loading by remember { mutableStateOf(false) }
    var readyEpoch by remember { mutableIntStateOf(0) }
    var readySourceName by remember { mutableStateOf<String?>(null) }
    var hotApplyJob by remember { mutableStateOf<Job?>(null) }
    var restartReason by remember { mutableStateOf<String?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    var pendingEnable by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    var inUseByOther by remember { mutableStateOf(false) }

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    fun startLoad(wantOn: Boolean) {
        hotApplyJob?.cancel()
        hotApplyJob = null

        enabled = wantOn
        MicManager.setEnabled(context, wantOn)

        if (!wantOn) {
            loading = false
            level = 0f
            readySourceName = null
            inUseByOther = false
            hotApplyJob = scope.launch {
                MicManager.hotApplyAndGetSource(false)
            }
            return
        }

        loading = true
        level = 0f
        readySourceName = null
        hotApplyJob = scope.launch {
            val (ok, name) = MicManager.hotApplyAndGetSource(true)
            if (!isActive) return@launch
            loading = false
            if (ok) {
                readySourceName = name
                readyEpoch++
                toastMsg = context.getString(R.string.app_micsel_toast_enabled)
            } else {
                restartReason = context.getString(R.string.app_micsel_restart_enable_reason)
            }
        }
    }

    val requestPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            toastMsg = context.getString(R.string.app_micsel_toast_denied)
            return@rememberLauncherForActivityResult
        }
        startLoad(pendingEnable)
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
        startLoad(wantOn)
    }

    // ── Inline VU ──
    // Runs parec only while (a) switch on, (b) drawer open, (c) app resumed,
    // (d) no other app already recording. Yields within ~1 s if any external
    // app opens the mic — so games/voice chat/ffmpeg always get a clean stream.
    LaunchedEffect(enabled, drawerState.isOpen, isResumed, readyEpoch) {
        if (!enabled || !drawerState.isOpen || !isResumed) {
            level = 0f
            inUseByOther = false
            return@LaunchedEffect
        }
        var name = readySourceName
        if (name == null) {
            repeat(20) {
                if (!isActive || !enabled) return@LaunchedEffect
                val n = MicManager.getSourceName()
                if (n != null) { name = n; return@repeat }
                kotlinx.coroutines.delay(100)
            }
        }
        if (name == null) return@LaunchedEffect

        while (isActive) {
            if (MicManager.countExternalRecorders() > 0) {
                inUseByOther = true
                level = 0f
                kotlinx.coroutines.delay(1000)
                continue
            }
            inUseByOther = false
            val meterJob = launch { MicManager.levelFlow(name).collect { level = it } }
            while (isActive && MicManager.countExternalRecorders() == 0) {
                kotlinx.coroutines.delay(1000)
            }
            meterJob.cancel()
            level = 0f
            kotlinx.coroutines.delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { hotApplyJob?.cancel() }
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
            Spacer(Modifier.height(4.dp))

            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Starting microphone…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            } else if (inUseByOther) {
                Text(
                    text = "In use by another app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .height(10.dp)
                        .background(Color(0xFF1F1F1F), RoundedCornerShape(5.dp))
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
                                shape = RoundedCornerShape(5.dp)
                            )
                    )
                }
                Text(
                    text = stringResource(R.string.app_micsel_monitor_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }
    }

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
}