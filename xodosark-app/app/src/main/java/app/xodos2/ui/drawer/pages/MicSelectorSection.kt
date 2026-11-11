package app.xodos2.ui.drawer.pages

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}

@Composable
fun MicSelectorSection(
    drawerState: DrawerState,
    onRestartRequested: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // VU depends on lifecycle state so it stops when the app is backgrounded
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
    var restartReason by remember { mutableStateOf<String?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    var pendingEnable by remember { mutableStateOf(false) }

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

    // ── Live VU — only while section is composed, drawer is open, and app is resumed
    var level by remember { mutableStateOf(0f) }
    LaunchedEffect(enabled, drawerState.isOpen, isResumed) {
        if (!enabled || !drawerState.isOpen || !isResumed) {
            level = 0f
            return@LaunchedEffect
        }
        val name = MicManager.getSourceName() ?: return@LaunchedEffect
        MicManager.levelFlow(name).collect { level = it }
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

        // ── Inline VU bar (visible only when enabled) ──
        if (enabled) {
            Spacer(Modifier.height(4.dp))
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
}