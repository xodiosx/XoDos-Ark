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
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale

private const val PREFS_NAME = "miroir_settings"
private val secureRandom = SecureRandom()

private const val AUTH_NONE  = 0
private const val AUTH_PIN   = 1
private const val AUTH_BASIC = 2

// The exact keys used by Miroir's original Settings.java, so prefs are compatible
private const val K_FPS              = "fps"
private const val K_QUALITY          = "quality"
private const val K_PORT             = "port"
private const val K_AUDIO_ENABLED    = "audio_enabled"
private const val K_AUDIO_SR         = "audio_sample_rate"
private const val K_AUDIO_CH         = "audio_channels"
private const val K_AUDIO_ENC        = "audio_encoding"
private const val K_MAX_W            = "max_width"
private const val K_MAX_H            = "max_height"
private const val K_BITRATE          = "bitrate"
private const val K_AUTH_MODE        = "auth_mode"
private const val K_PIN              = "pin"
private const val K_BASIC_USER       = "basic_user"
private const val K_BASIC_PASS       = "basic_pass"
private const val K_AUTO_RESTART     = "auto_restart"
private const val K_HTTPS            = "https_enabled"
private const val K_KEEP_AWAKE       = "keep_awake"

@Composable
fun MirrorSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, 0) }

    // ── State ────────────────────────────────────────────────────
    var fps           by remember { mutableIntStateOf(prefs.getInt(K_FPS, 24)) }
    var quality       by remember { mutableIntStateOf(prefs.getInt(K_QUALITY, 70)) }
    var port          by remember { mutableIntStateOf(prefs.getInt(K_PORT, 8080)) }
    var audioEnabled  by remember { mutableStateOf(prefs.getBoolean(K_AUDIO_ENABLED, true)) }
    var audioSr       by remember { mutableIntStateOf(prefs.getInt(K_AUDIO_SR, 44100)) }
    var audioCh       by remember { mutableIntStateOf(prefs.getInt(K_AUDIO_CH, 2)) }
    var audioEnc      by remember { mutableIntStateOf(prefs.getInt(K_AUDIO_ENC, AudioFormat.ENCODING_PCM_16BIT)) }
    var maxW          by remember { mutableIntStateOf(prefs.getInt(K_MAX_W, 1280)) }
    var maxH          by remember { mutableIntStateOf(prefs.getInt(K_MAX_H, 720)) }
    var bitrate       by remember { mutableIntStateOf(prefs.getInt(K_BITRATE, 4000)) }
    var authMode      by remember { mutableIntStateOf(prefs.getInt(K_AUTH_MODE, AUTH_NONE)) }
    var pin           by remember { mutableStateOf(prefs.getString(K_PIN, null) ?: generatePin()) }
    var basicUser     by remember { mutableStateOf(prefs.getString(K_BASIC_USER, "viewer") ?: "viewer") }
    var basicPass     by remember { mutableStateOf(prefs.getString(K_BASIC_PASS, null) ?: generatePassword()) }
    var autoRestart   by remember { mutableStateOf(prefs.getBoolean(K_AUTO_RESTART, false)) }
    var httpsEnabled  by remember { mutableStateOf(prefs.getBoolean(K_HTTPS, false)) }
    var keepAwake     by remember { mutableStateOf(prefs.getBoolean(K_KEEP_AWAKE, true)) }

    var showAdvanced  by remember { mutableStateOf(false) }
    var isStreaming   by remember { mutableStateOf(isMiroirRunning(context)) }

    // ── IP list & picker ────────────────────────────────────────
    val allIps = remember { getAllLocalIps() }
    var selectedIp by remember { mutableStateOf(allIps.firstOrNull() ?: "") }
    if (selectedIp.isEmpty() && allIps.isNotEmpty()) {
        selectedIp = allIps.first()
    }

    // ── Precomputed option lists ────────────────────────────────
    val resolutionOptions = listOf(854 to 480, 1280 to 720, 1920 to 1080)
    val resolutionLabels = listOf("480p", "720p", "1080p")
    val resolutionIdx = resolutionOptions.indexOfFirst { it.first == maxW && it.second == maxH }
        .takeIf { it >= 0 } ?: 1

    val bitrateOptions = listOf(1000, 2000, 4000, 6000, 8000)

    val sampleRates = listOf(16000, 22050, 44100, 48000)
    val sampleRateLabels = listOf(
        stringResource(R.string.app_mirror_sr_16k),
        stringResource(R.string.app_mirror_sr_22k),
        stringResource(R.string.app_mirror_sr_44k),
        stringResource(R.string.app_mirror_sr_48k),
    )
    val encodings = listOf(
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_8BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
    )
    val encodingLabels = listOf(
        stringResource(R.string.app_mirror_enc_pcm16),
        stringResource(R.string.app_mirror_enc_pcm8),
        stringResource(R.string.app_mirror_enc_float),
    )

    // ── Computed URL (always visible) ───────────────────────────
    val proto = if (httpsEnabled) "https" else "http"
    val displayIp = selectedIp.ifEmpty { "0.0.0.0" }
    val computedUrl = buildString {
        append(proto); append("://"); append(displayIp); append(":"); append(port)
        if (authMode == AUTH_PIN) { append("/?pin="); append(pin) }
    }
    val noNetwork = displayIp == "0.0.0.0" || allIps.isEmpty()

    // ── Persist ─────────────────────────────────────────────────
    fun saveAll() {
        prefs.edit()
            .putInt(K_FPS, fps)
            .putInt(K_QUALITY, quality)
            .putInt(K_PORT, port)
            .putBoolean(K_AUDIO_ENABLED, audioEnabled)
            .putInt(K_AUDIO_SR, audioSr)
            .putInt(K_AUDIO_CH, audioCh)
            .putInt(K_AUDIO_ENC, audioEnc)
            .putInt(K_MAX_W, maxW)
            .putInt(K_MAX_H, maxH)
            .putInt(K_BITRATE, bitrate)
            .putInt(K_AUTH_MODE, authMode)
            .putString(K_PIN, pin)
            .putString(K_BASIC_USER, basicUser)
            .putString(K_BASIC_PASS, basicPass)
            .putBoolean(K_AUTO_RESTART, autoRestart)
            .putBoolean(K_HTTPS, httpsEnabled)
            .putBoolean(K_KEEP_AWAKE, keepAwake)
            .apply()
    }

    // ── Presets — mirror Miroir's MainActivity.applyPreset() ────
    fun applyPreset(name: String) {
        if (isStreaming) return
        when (name) {
            "presentation" -> {
                fps = 15; quality = 70; audioEnabled = false
                maxW = 1280; maxH = 720; bitrate = 4000
                audioSr = 44100; audioCh = 2
                audioEnc = AudioFormat.ENCODING_PCM_16BIT
                keepAwake = true
                authMode = AUTH_NONE
            }
            "gaming" -> {
                fps = 60; quality = 85; audioEnabled = true
                maxW = 1920; maxH = 1080; bitrate = 8000
                audioSr = 48000; audioCh = 2
                audioEnc = AudioFormat.ENCODING_PCM_16BIT
                keepAwake = true
                authMode = AUTH_NONE
            }
            "dashboard" -> {
                fps = 5; quality = 50; audioEnabled = false
                maxW = 1280; maxH = 720; bitrate = 2000
                audioSr = 44100; audioCh = 2
                audioEnc = AudioFormat.ENCODING_PCM_16BIT
                keepAwake = true
                authMode = AUTH_NONE
            }
            "low_bw" -> {
                fps = 10; quality = 40; audioEnabled = false
                maxW = 854; maxH = 480; bitrate = 1000
                audioSr = 44100; audioCh = 2
                audioEnc = AudioFormat.ENCODING_PCM_16BIT
                keepAwake = false
                authMode = AUTH_NONE
            }
        }
        saveAll()
    }

    // ── Stopped broadcast ───────────────────────────────────────
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == MiroirService.ACTION_STOPPED) {
                    isStreaming = false
                    // Auto-restart only meaningful while dialog is open (needs user tap
                    // for MediaProjection permission on every restart anyway).
                    if (autoRestart) {
                        // will require user interaction — we simply show a hint below
                    }
                }
            }
        }
        val filter = IntentFilter(MiroirService.ACTION_STOPPED)
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // ── Launchers ───────────────────────────────────────────────
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
                audioCh = audioCh, audioEnc = audioEnc,
                maxWidth = maxW, maxHeight = maxH, bitrate = bitrate,
                authMode = authMode, pin = pin,
                basicUser = basicUser, basicPass = basicPass,
                httpsEnabled = httpsEnabled,
                keepAwake = keepAwake
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
        context.startService(
            Intent(context, MiroirService::class.java).apply {
                action = MiroirService.ACTION_STOP
            }
        )
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
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Status ───────────────────────────────────────
                Text(
                    stringResource(
                        if (isStreaming) R.string.app_mirror_status_live
                        else R.string.app_mirror_status_stopped
                    ),
                    color = if (isStreaming) Color(0xFF22C55E) else Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(10.dp))

                // ── URL (always shown) ───────────────────────────
                if (noNetwork) {
                    Text(
                        stringResource(R.string.app_mirror_status_no_network),
                        color = Color(0xFFF59E0B),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    OutlinedTextField(
                        value = computedUrl,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = {
                            Text(
                                stringResource(
                                    if (isStreaming) R.string.app_mirror_stream_url_live
                                    else R.string.app_mirror_stream_url_preview
                                )
                            )
                        },
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
                                    .setPrimaryClip(ClipData.newPlainText("URL", computedUrl))
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
                                        context.getString(R.string.app_mirror_share_text, computedUrl)
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

                    // ── IP picker ────────────────────────────────
                    if (allIps.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        var ipExpanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(
                                onClick = { ipExpanded = true },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    stringResource(R.string.app_mirror_ip_label) + ": " + displayIp + " ▼",
                                    color = Color(0xFFC3B6F9),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            DropdownMenu(
                                expanded = ipExpanded,
                                onDismissRequest = { ipExpanded = false }
                            ) {
                                allIps.forEach { ip ->
                                    DropdownMenuItem(
                                        text = { Text(ip) },
                                        onClick = { selectedIp = ip; ipExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(10.dp))

                // ── Presets ──────────────────────────────────────
                SectionLabel(stringResource(R.string.app_mirror_label_presets))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "presentation" to stringResource(R.string.app_mirror_preset_presentation),
                        "gaming"       to stringResource(R.string.app_mirror_preset_gaming),
                    ).forEach { (k, label) ->
                        OutlinedButton(
                            onClick = { applyPreset(k) },
                            enabled = !isStreaming,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "dashboard" to stringResource(R.string.app_mirror_preset_dashboard),
                        "low_bw"    to stringResource(R.string.app_mirror_preset_low_bw),
                    ).forEach { (k, label) ->
                        OutlinedButton(
                            onClick = { applyPreset(k) },
                            enabled = !isStreaming,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text(label, style = MaterialTheme.typography.bodySmall) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(10.dp))

                // ── Simple / Advanced tabs ───────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !showAdvanced,
                        onClick = { showAdvanced = false },
                        label = { Text(stringResource(R.string.app_mirror_tab_simple)) }
                    )
                    FilterChip(
                        selected = showAdvanced,
                        onClick = { showAdvanced = true },
                        label = { Text(stringResource(R.string.app_mirror_tab_advanced)) }
                    )
                }
                Spacer(Modifier.height(10.dp))

                // ── FPS (always visible) ─────────────────────────
                SectionLabel(stringResource(R.string.app_mirror_label_fps))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(5, 10, 15, 24, 30, 60).forEach { f ->
                        FilterChip(
                            selected = fps == f,
                            onClick = { if (!isStreaming) { fps = f; saveAll() } },
                            enabled = !isStreaming,
                            label = { Text("$f") }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ── Quality ──────────────────────────────────────
                SectionLabel(stringResource(R.string.app_mirror_label_quality, quality))
                Slider(
                    value = quality.toFloat(),
                    onValueChange = {
                        if (!isStreaming) {
                            quality = it.toInt().coerceIn(10, 100)
                            saveAll()
                        }
                    },
                    valueRange = 10f..100f,
                    enabled = !isStreaming
                )

                // ════════════════════════════════════════════════
                //  SIMPLE-ONLY section
                // ════════════════════════════════════════════════
                if (!showAdvanced) {
                    SectionLabel(stringResource(R.string.app_mirror_label_resolution))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        resolutionLabels.forEachIndexed { i, label ->
                            FilterChip(
                                selected = resolutionIdx == i,
                                onClick = {
                                    if (!isStreaming) {
                                        maxW = resolutionOptions[i].first
                                        maxH = resolutionOptions[i].second
                                        saveAll()
                                    }
                                },
                                enabled = !isStreaming,
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Audio on/off + (when on) sample rate / channels / encoding
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_mirror_label_audio),
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = audioEnabled,
                            onCheckedChange = {
                                if (!isStreaming) { audioEnabled = it; saveAll() }
                            },
                            enabled = !isStreaming
                        )
                    }
                    if (audioEnabled) {
                        Spacer(Modifier.height(4.dp))
                        SectionLabel(stringResource(R.string.app_mirror_label_audio_sample_rate))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            sampleRates.forEachIndexed { i, sr ->
                                FilterChip(
                                    selected = audioSr == sr,
                                    onClick = { if (!isStreaming) { audioSr = sr; saveAll() } },
                                    enabled = !isStreaming,
                                    label = { Text(sampleRateLabels[i], style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        SectionLabel(stringResource(R.string.app_mirror_label_audio_channels))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = audioCh == 1,
                                onClick = { if (!isStreaming) { audioCh = 1; saveAll() } },
                                enabled = !isStreaming,
                                label = { Text(stringResource(R.string.app_mirror_ch_mono)) }
                            )
                            FilterChip(
                                selected = audioCh == 2,
                                onClick = { if (!isStreaming) { audioCh = 2; saveAll() } },
                                enabled = !isStreaming,
                                label = { Text(stringResource(R.string.app_mirror_ch_stereo)) }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        SectionLabel(stringResource(R.string.app_mirror_label_audio_encoding))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            encodings.forEachIndexed { i, enc ->
                                FilterChip(
                                    selected = audioEnc == enc,
                                    onClick = { if (!isStreaming) { audioEnc = enc; saveAll() } },
                                    enabled = !isStreaming,
                                    label = { Text(encodingLabels[i], style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Port
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

                    Spacer(Modifier.height(8.dp))

                    // Auth
                    SectionLabel(stringResource(R.string.app_mirror_label_authentication))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val authLabels = listOf(
                            stringResource(R.string.app_mirror_auth_none),
                            stringResource(R.string.app_mirror_auth_pin),
                            stringResource(R.string.app_mirror_auth_basic),
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
                        OutlinedTextField(
                            value = basicUser,
                            onValueChange = { if (!isStreaming) { basicUser = it; saveAll() } },
                            label = { Text(stringResource(R.string.app_mirror_label_basic_user)) },
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
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = basicPass,
                                onValueChange = { if (!isStreaming) { basicPass = it; saveAll() } },
                                label = { Text(stringResource(R.string.app_mirror_label_basic_pass)) },
                                singleLine = true,
                                enabled = !isStreaming,
                                modifier = Modifier.weight(1f),
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
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                onClick = { basicPass = generatePassword(); saveAll() },
                                enabled = !isStreaming
                            ) { Text(stringResource(R.string.app_mirror_regenerate)) }
                        }
                    }
                }

                // ════════════════════════════════════════════════
                //  ADVANCED-ONLY section
                // ════════════════════════════════════════════════
                if (showAdvanced) {
                    // Bitrate
                    SectionLabel(stringResource(R.string.app_mirror_label_bitrate))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        bitrateOptions.forEach { b ->
                            FilterChip(
                                selected = bitrate == b,
                                onClick = { if (!isStreaming) { bitrate = b; saveAll() } },
                                enabled = !isStreaming,
                                label = {
                                    Text(
                                        stringResource(R.string.app_mirror_bitrate_kbps, b),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Auto-restart
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_mirror_label_auto_restart),
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = autoRestart,
                            onCheckedChange = { if (!isStreaming) { autoRestart = it; saveAll() } },
                            enabled = !isStreaming
                        )
                    }

                    // HTTPS
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_mirror_label_https),
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = httpsEnabled,
                            onCheckedChange = { if (!isStreaming) { httpsEnabled = it; saveAll() } },
                            enabled = !isStreaming
                        )
                    }
                    if (httpsEnabled) {
                        Text(
                            stringResource(R.string.app_mirror_label_https_warn),
                            color = Color(0xFFF59E0B),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    // Keep awake
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.app_mirror_label_keep_awake),
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = keepAwake,
                            onCheckedChange = { if (!isStreaming) { keepAwake = it; saveAll() } },
                            enabled = !isStreaming
                        )
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
    audioEnabled: Boolean, audioSr: Int, audioCh: Int, audioEnc: Int,
    maxWidth: Int, maxHeight: Int, bitrate: Int,
    authMode: Int, pin: String,
    basicUser: String, basicPass: String,
    httpsEnabled: Boolean, keepAwake: Boolean
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
        putExtra(MiroirService.EXTRA_HTTPS, httpsEnabled)
        putExtra(MiroirService.EXTRA_KEEP_AWAKE, keepAwake)
        putExtra(MiroirService.EXTRA_AUDIO_SR, audioSr)
        putExtra(MiroirService.EXTRA_AUDIO_CH, audioCh)
        putExtra(MiroirService.EXTRA_AUDIO_ENC, audioEnc)
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

/** All usable IPv4 addresses on the device — same filtering as Miroir's original. */
private fun getAllLocalIps(): List<String> {
    val ips = mutableListOf<String>()
    try {
        for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
            val n = iface.name.lowercase(Locale.ROOT)
            if (n.contains("tun") || n.contains("tap") || n.contains("vpn") ||
                n.contains("ppp") || n.contains("docker") || n.contains("veth") ||
                n == "lo" || n.startsWith("lo:")) continue
            for (addr: InetAddress in Collections.list(iface.inetAddresses)) {
                val h = addr.hostAddress ?: continue
                if (addr.isLoopbackAddress || h.contains(":")) continue
                if (!ips.contains(h)) ips.add(h)
            }
        }
    } catch (_: Exception) {}
    return ips
}