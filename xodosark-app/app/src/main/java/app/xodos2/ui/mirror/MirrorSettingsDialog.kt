package app.xodos2.ui.mirror

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.xodos2.ui.glassDialogStyle
import com.miroir.MiroirService
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale

private const val PREFS_NAME = "miroir_settings"
private val secureRandom = SecureRandom()

private const val AUTH_NONE  = 0
private const val AUTH_PIN   = 1
private const val AUTH_BASIC = 2

@Composable
fun MirrorSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, 0) }

    // ── Persistent settings ─────────────────────────────────────
    var fps           by remember { mutableIntStateOf(prefs.getInt("fps", 24)) }
    var quality       by remember { mutableIntStateOf(prefs.getInt("quality", 70)) }
    var port          by remember { mutableIntStateOf(prefs.getInt("port", 8080)) }
    var audioEnabled  by remember { mutableStateOf(prefs.getBoolean("audio_enabled", true)) }
    var audioSr       by remember { mutableIntStateOf(prefs.getInt("audio_sr", 44100)) }
    var resolutionIdx by remember { mutableIntStateOf(prefs.getInt("resolution", 1)) } // 0=480p 1=720p 2=1080p
    var bitrate       by remember { mutableIntStateOf(prefs.getInt("bitrate", 4000)) }
    var authMode      by remember { mutableIntStateOf(prefs.getInt("auth_mode", AUTH_NONE)) }
    var pin           by remember { mutableStateOf(prefs.getString("pin", null) ?: generatePin()) }
    var basicUser     by remember { mutableStateOf(prefs.getString("basic_user", "viewer") ?: "viewer") }
    var basicPass     by remember { mutableStateOf(prefs.getString("basic_pass", null) ?: generatePassword()) }

    val resolutions = listOf(854 to 480, 1280 to 720, 1920 to 1080)

    // ── Live status ─────────────────────────────────────────────
    var isStreaming by remember { mutableStateOf(isMiroirRunning(context)) }
    var currentUrl  by remember { mutableStateOf("") }

    fun refreshUrlFromSettings() {
        if (!isStreaming) { currentUrl = ""; return }
        val ip = getLocalIpAddress()
        var url = "http://$ip:$port"
        if (authMode == AUTH_PIN) url += "/?pin=$pin"
        currentUrl = url
    }

    LaunchedEffect(isStreaming) { refreshUrlFromSettings() }

    // ── Stopped broadcast receiver ──────────────────────────────
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == MiroirService.ACTION_STOPPED) {
                    isStreaming = false
                }
            }
        }
        val filter = IntentFilter(MiroirService.ACTION_STOPPED)
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // ── Persist on change ───────────────────────────────────────
    fun saveAll() {
        prefs.edit()
            .putInt("fps", fps)
            .putInt("quality", quality)
            .putInt("port", port)
            .putBoolean("audio_enabled", audioEnabled)
            .putInt("audio_sr", audioSr)
            .putInt("resolution", resolutionIdx)
            .putInt("bitrate", bitrate)
            .putInt("auth_mode", authMode)
            .putString("pin", pin)
            .putString("basic_user", basicUser)
            .putString("basic_pass", basicPass)
            .apply()
    }

    // ── MediaProjection launcher ────────────────────────────────
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            saveAll()
            startMiroirService(
                context = context,
                resultCode = result.resultCode,
                data = result.data!!,
                fps = fps, quality = quality, port = port,
                audioEnabled = audioEnabled, audioSr = audioSr,
                maxWidth = resolutions[resolutionIdx].first,
                maxHeight = resolutions[resolutionIdx].second,
                bitrate = bitrate,
                authMode = authMode, pin = pin,
                basicUser = basicUser, basicPass = basicPass
            )
            isStreaming = true
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.app_mirror_toast_capture_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── Audio permission launcher ───────────────────────────────
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            audioEnabled = false
            Toast.makeText(
                context,
                context.getString(R.string.app_mirror_toast_audio_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
        launchProjection(context, projectionLauncher)
    }

    fun startStream() {
        if (audioEnabled && ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjection(context, projectionLauncher)
        }
    }

    fun stopStream() {
        val svc = Intent(context, MiroirService::class.java).apply {
            action = MiroirService.ACTION_STOP
        }
        context.startService(svc)
        isStreaming = false
    }

    // ── UI ──────────────────────────────────────────────────────
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = {
            Text(
                stringResource(R.string.app_mirror_dialog_title),
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Status ─────────────────────────────
                Text(
                    stringResource(
                        if (isStreaming) R.string.app_mirror_status_live
                        else R.string.app_mirror_status_stopped
                    ),
                    color = if (isStreaming) Color(0xFF22C55E) else Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold
                )

                if (isStreaming && currentUrl.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = currentUrl,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.app_mirror_view_at)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFC3B6F9),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedLabelColor = Color(0xFFC3B6F9),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                            cursorColor = Color(0xFFC3B6F9)
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("URL", currentUrl))
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.app_mirror_url_copied),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.app_mirror_copy_url)) }

                        OutlinedButton(
                            onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        context.getString(R.string.app_mirror_share_text, currentUrl)
                                    )
                                }
                                context.startActivity(
                                    Intent.createChooser(
                                        share,
                                        context.getString(R.string.app_mirror_share_chooser)
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.app_mirror_share)) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(12.dp))

                // ── FPS ────────────────────────────────
                SectionLabel(stringResource(R.string.app_mirror_label_fps))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 24, 30, 60).forEach { f ->
                        FilterChip(
                            selected = fps == f,
                            onClick = { if (!isStreaming) { fps = f; saveAll() } },
                            enabled = !isStreaming,
                            label = { Text("$f") }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Quality ────────────────────────────
                SectionLabel(stringResource(R.string.app_mirror_label_quality, quality))
                Slider(
                    value = quality.toFloat(),
                    onValueChange = { if (!isStreaming) { quality = it.toInt().coerceIn(10, 100); saveAll() } },
                    valueRange = 10f..100f,
                    enabled = !isStreaming
                )

                // ── Resolution ─────────────────────────
                SectionLabel(stringResource(R.string.app_mirror_label_resolution))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("480p", "720p", "1080p").forEachIndexed { i, label ->
                        FilterChip(
                            selected = resolutionIdx == i,
                            onClick = { if (!isStreaming) { resolutionIdx = i; saveAll() } },
                            enabled = !isStreaming,
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Audio ──────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.app_mirror_label_audio),
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = audioEnabled,
                        onCheckedChange = { if (!isStreaming) { audioEnabled = it; saveAll() } },
                        enabled = !isStreaming
                    )
                }

                // ── Port ───────────────────────────────
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port.toString(),
                    onValueChange = { txt ->
                        if (!isStreaming) {
                            val p = txt.toIntOrNull()
                            if (p != null && p in 1..65535) { port = p; saveAll() }
                        }
                    },
                    label = { Text(stringResource(R.string.app_mirror_label_port)) },
                    singleLine = true,
                    enabled = !isStreaming,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFC3B6F9),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = Color(0xFFC3B6F9),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                        cursorColor = Color(0xFFC3B6F9)
                    )
                )

                // ── Auth ───────────────────────────────
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.app_mirror_label_authentication))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val authLabels = listOf(
                        stringResource(R.string.app_mirror_auth_none),
                        stringResource(R.string.app_mirror_auth_pin),
                        stringResource(R.string.app_mirror_auth_basic)
                    )
                    authLabels.forEachIndexed { i, label ->
                        FilterChip(
                            selected = authMode == i,
                            onClick = { if (!isStreaming) { authMode = i; saveAll() } },
                            enabled = !isStreaming,
                            label = { Text(label) }
                        )
                    }
                }
                if (authMode == AUTH_PIN) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_mirror_label_pin, pin),
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { pin = generatePin(); saveAll() },
                            enabled = !isStreaming
                        ) { Text(stringResource(R.string.app_mirror_regenerate)) }
                    }
                } else if (authMode == AUTH_BASIC) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_mirror_label_basic, basicUser, basicPass),
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { basicPass = generatePassword(); saveAll() },
                            enabled = !isStreaming
                        ) { Text(stringResource(R.string.app_mirror_new_pass)) }
                    }
                }
            }
        },
        confirmButton = {
            if (isStreaming) {
                Button(
                    onClick = { stopStream() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) { Text(stringResource(R.string.app_mirror_stop)) }
            } else {
                Button(
                    onClick = { saveAll(); startStream() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))
                ) { Text(stringResource(R.string.app_mirror_start)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.app_mirror_close),
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    )
}

/* ─────────────── helpers ─────────────── */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

private fun launchProjection(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    launcher.launch(mpm.createScreenCaptureIntent())
}

private fun startMiroirService(
    context: Context,
    resultCode: Int,
    data: Intent,
    fps: Int, quality: Int, port: Int,
    audioEnabled: Boolean, audioSr: Int,
    maxWidth: Int, maxHeight: Int, bitrate: Int,
    authMode: Int, pin: String,
    basicUser: String, basicPass: String
) {
    val authModeName = when (authMode) {
        AUTH_PIN   -> "PIN"
        AUTH_BASIC -> "BASIC"
        else       -> "NONE"
    }
    val svc = Intent(context, MiroirService::class.java).apply {
        action = MiroirService.ACTION_START
        putExtra(MiroirService.EXTRA_RESULT_CODE, resultCode)
        putExtra(MiroirService.EXTRA_DATA, data)
        putExtra(MiroirService.EXTRA_PORT, port)
        putExtra(MiroirService.EXTRA_QUALITY, quality)
        putExtra(MiroirService.EXTRA_FPS, fps)
        putExtra(MiroirService.EXTRA_AUDIO, audioEnabled)
        putExtra(MiroirService.EXTRA_HTTPS, false)
        putExtra(MiroirService.EXTRA_KEEP_AWAKE, true)
        putExtra(MiroirService.EXTRA_AUDIO_SR, audioSr)
        putExtra(MiroirService.EXTRA_AUDIO_CH, 2)
        putExtra(MiroirService.EXTRA_AUDIO_ENC, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(MiroirService.EXTRA_MAX_WIDTH, maxWidth)
        putExtra(MiroirService.EXTRA_MAX_HEIGHT, maxHeight)
        putExtra(MiroirService.EXTRA_BITRATE, bitrate)
        putExtra(MiroirService.EXTRA_AUTH_MODE, authModeName)
        putExtra(MiroirService.EXTRA_PIN, pin)
        putExtra(MiroirService.EXTRA_BASIC_USER, basicUser)
        putExtra(MiroirService.EXTRA_BASIC_PASS, basicPass)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(svc)
    } else {
        context.startService(svc)
    }
}

private fun isMiroirRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    return am.getRunningServices(Int.MAX_VALUE).any {
        it.service.className == MiroirService::class.java.name
    }
}

private fun generatePin(): String =
    String.format(Locale.US, "%06d", secureRandom.nextInt(1_000_000))

private fun generatePassword(): String {
    val raw = ByteArray(9).also { secureRandom.nextBytes(it) }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    } else {
        android.util.Base64.encodeToString(
            raw,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }
}

private fun getLocalIpAddress(): String {
    try {
        for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            val n = iface.name.lowercase(Locale.ROOT)
            if (n.startsWith("wlan") || n.startsWith("wifi") ||
                n.startsWith("eth") || n.startsWith("enp")) {
                for (addr in Collections.list(iface.inetAddresses)) {
                    val h = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress || h.contains(":")) continue
                    return h
                }
            }
        }
        for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            val n = iface.name.lowercase(Locale.ROOT)
            if (n.contains("tun") || n.contains("tap") || n.contains("vpn") ||
                n.contains("ppp") || n.contains("docker") || n.contains("veth") ||
                n == "lo" || n.startsWith("lo:")) continue
            for (addr in Collections.list(iface.inetAddresses)) {
                val h = addr.hostAddress ?: continue
                if (addr.isLoopbackAddress || h.contains(":")) continue
                return h
            }
        }
    } catch (_: Exception) {}
    return "0.0.0.0"
}