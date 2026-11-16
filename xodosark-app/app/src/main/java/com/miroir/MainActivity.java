package com.miroir;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.annotation.SuppressLint;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.annotation.SuppressLint;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;

import java.io.File;
import java.io.FileOutputStream;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG          = "Miroir";
    private static final int    DEFAULT_PORT = 8080;

    private static final SecureRandom secureRandom = new SecureRandom();

    private enum Preset {
        PRESENTATION,
        GAMING,
        DASHBOARD,
        LOW_BANDWIDTH
    }

    private TextView tvStatus, tvUrl, tvUrlLabel, tvUrlHint, tvQuality, tvFpsLabel, tvPin, tvPinSimple;
    private Button   btnToggle, btnFps5, btnFps10, btnFps15, btnFps24, btnFps30, btnFps60;
    private Button   btnPinRegen, btnPinRegenSimple, btnBasicRegen, btnBasicRegenSimple;
    private SeekBar      seekBarQuality;
    private SwitchCompat switchAudio;
    private SwitchCompat switchAutoRestart;
    private SwitchCompat switchHttps;
    private SwitchCompat switchKeepAwake;
    private Button     btnTabSimple, btnTabAdvanced;
    private LinearLayout layoutSimple, layoutAdvanced;
    private Button     btnPresetPres, btnPresetGaming, btnPresetDashboard, btnPresetLowBW;
    private Spinner  spinnerSampleRate, spinnerChannels, spinnerEncoding, spinnerAuthMode;
    private ImageButton btnIpDropdown;
    private Spinner  spinnerAuthModeSimple;
    private Spinner  spinnerResolution, spinnerBitrate;
    private Spinner  spinnerResolutionAdv, spinnerBitrateAdv;
    private ImageButton  btnQrCode;
    private EditText editPort, editBasicUser, editBasicPass, editBasicUserSimple, editBasicPassSimple;
    private View     layoutPinRow, layoutPinRowSimple, layoutBasicRow, layoutBasicRowSimple;

    private Settings settings;

    private boolean isStreaming        = false;
    private boolean autoRestart        = false;
    private int     selectedFps        = 24;
    private boolean audioEnabled       = true;
    private boolean httpsEnabled       = false;
    private boolean keepAwake          = true;
    private int     selectedSampleRate = 44100;
    private int     selectedChannels   = 2;
    private int     selectedEncoding   = AudioFormat.ENCODING_PCM_16BIT;
    private int     selectedPort       = DEFAULT_PORT;
    private int     maxWidthPref     = 1280;
    private int     maxHeightPref    = 720;
    private int     bitratePref      = 4000; // kbps
    private String  currentUrl         = "";

    private MiroirService.AuthMode authMode  = MiroirService.AuthMode.NONE;
    private String currentPin;
    private String basicUser;
    private String basicPass;

    private final BroadcastReceiver stoppedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MiroirService.ACTION_STOPPED.equals(intent.getAction())) {
                isStreaming = false;
                updateStatusStopped();
                if (autoRestart) {
                    requestCapturePermission();
                }
            }
        }
    };

    private final ActivityResultLauncher<Intent> projectionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                startStreamingService(result.getResultCode(), result.getData());
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show();
            }
        });

    // RECORD_AUDIO is a dangerous permission and is required for AudioPlaybackCaptureConfiguration
    // even though it captures device playback, not the microphone. Without this, AudioRecord
    // construction throws SecurityException and audio silently never works.
    private final ActivityResultLauncher<String> audioPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (!granted) {
                Toast.makeText(this, "Audio permission denied, streaming without audio", Toast.LENGTH_SHORT).show();
            }
            launchCapturePermission();
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        settings = new Settings(this);

        bindViewsAndListeners();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        ErrorReporter.get().init(this);
        ErrorReporter.get().addListener(error -> {
            if (error.level == ErrorReporter.Level.ERROR || error.level == ErrorReporter.Level.FATAL) {
                runOnUiThread(() -> showErrorDialog(error));
            }
        });

        handleShortcutIntent(getIntent());
        updateStatusStopped();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShortcutIntent(intent);
    }

    private void handleShortcutIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case "com.miroir.START_STREAM":
                if (!isStreaming) requestCapturePermission();
                break;
            case "com.miroir.STOP_STREAM":
                if (isStreaming) stopStreaming();
                break;
            case "com.miroir.PRESET_PRESENTATION":
                applyPreset(Preset.PRESENTATION);
                if (!isStreaming) requestCapturePermission();
                break;
            case "com.miroir.PRESET_GAMING":
                applyPreset(Preset.GAMING);
                if (!isStreaming) requestCapturePermission();
                break;
            case "com.miroir.PRESET_DASHBOARD":
                applyPreset(Preset.DASHBOARD);
                if (!isStreaming) requestCapturePermission();
                break;
            case "com.miroir.PRESET_LOW_BANDWIDTH":
                applyPreset(Preset.LOW_BANDWIDTH);
                if (!isStreaming) requestCapturePermission();
                break;
        }
    }

    private void applyPreset(Preset preset) {
        switch (preset) {
            case PRESENTATION:
                setFps(15);
                seekBarQuality.setProgress(70);
                switchAudio.setChecked(false);
                editPort.setText("8080");
                spinnerAuthMode.setSelection(0); // None
                switchKeepAwake.setChecked(true);
                // Video: 1280x720 (720p), 4 Mbps
                maxWidthPref = 1280; maxHeightPref = 720; bitratePref = 4000;
                updateResolutionSpinner(1280, 720);
                updateBitrateSpinner(4000);
                // Audio defaults (44.1kHz, stereo, 16-bit)
                selectedSampleRate = 44100; spinnerSampleRate.setSelection(2);
                selectedChannels = 2; spinnerChannels.setSelection(1);
                selectedEncoding = AudioFormat.ENCODING_PCM_16BIT; spinnerEncoding.setSelection(0);
                break;
            case GAMING:
                setFps(60);
                seekBarQuality.setProgress(85);
                switchAudio.setChecked(true);
                editPort.setText("8080");
                spinnerAuthMode.setSelection(0); // None
                switchKeepAwake.setChecked(true);
                // Video: 1920x1080 (1080p), 8 Mbps
                maxWidthPref = 1920; maxHeightPref = 1080; bitratePref = 8000;
                updateResolutionSpinner(1920, 1080);
                updateBitrateSpinner(8000);
                // Audio: 48kHz, stereo, 16-bit
                selectedSampleRate = 48000; spinnerSampleRate.setSelection(3);
                selectedChannels = 2; spinnerChannels.setSelection(1);
                selectedEncoding = AudioFormat.ENCODING_PCM_16BIT; spinnerEncoding.setSelection(0);
                break;
            case DASHBOARD:
                setFps(5);
                seekBarQuality.setProgress(50);
                switchAudio.setChecked(false);
                editPort.setText("8080");
                spinnerAuthMode.setSelection(0); // None
                switchKeepAwake.setChecked(true);
                // Video: 1280x720 (720p), 2 Mbps
                maxWidthPref = 1280; maxHeightPref = 720; bitratePref = 2000;
                updateResolutionSpinner(1280, 720);
                updateBitrateSpinner(2000);
                // Audio defaults
                selectedSampleRate = 44100; spinnerSampleRate.setSelection(2);
                selectedChannels = 2; spinnerChannels.setSelection(1);
                selectedEncoding = AudioFormat.ENCODING_PCM_16BIT; spinnerEncoding.setSelection(0);
                break;
            case LOW_BANDWIDTH:
                setFps(10);
                seekBarQuality.setProgress(40);
                switchAudio.setChecked(false);
                editPort.setText("8080");
                spinnerAuthMode.setSelection(0); // None
                switchKeepAwake.setChecked(false);
                // Video: 854x480 (480p), 1 Mbps
                maxWidthPref = 854; maxHeightPref = 480; bitratePref = 1000;
                updateResolutionSpinner(854, 480);
                updateBitrateSpinner(1000);
                // Audio defaults
                selectedSampleRate = 44100; spinnerSampleRate.setSelection(2);
                selectedChannels = 2; spinnerChannels.setSelection(1);
                selectedEncoding = AudioFormat.ENCODING_PCM_16BIT; spinnerEncoding.setSelection(0);
                break;
        }
    }

    private void updateResolutionSpinner(int w, int h) {
        String target = w + "x" + h;
        // Update both simple and advanced spinners
        for (int i = 0; i < spinnerResolution.getCount(); i++) {
            if (spinnerResolution.getItemAtPosition(i).toString().contains(w + "x" + h)) {
                spinnerResolution.setSelection(i);
                break;
            }
        }
        for (int i = 0; i < spinnerResolutionAdv.getCount(); i++) {
            if (spinnerResolutionAdv.getItemAtPosition(i).toString().contains(w + "x" + h)) {
                spinnerResolutionAdv.setSelection(i);
                break;
            }
        }
    }

    private void updateBitrateSpinner(int kbps) {
        String target = kbps + " kbps";
        for (int i = 0; i < spinnerBitrate.getCount(); i++) {
            if (spinnerBitrate.getItemAtPosition(i).toString().contains(kbps + "")) {
                spinnerBitrate.setSelection(i);
                break;
            }
        }
        for (int i = 0; i < spinnerBitrateAdv.getCount(); i++) {
            if (spinnerBitrateAdv.getItemAtPosition(i).toString().contains(kbps + "")) {
                spinnerBitrateAdv.setSelection(i);
                break;
            }
        }
    }

    private void bindViewsAndListeners() {
        tvStatus          = findViewById(R.id.tv_status);
        tvUrl             = findViewById(R.id.tv_url);
        tvUrlLabel        = findViewById(R.id.tv_url_label);
        tvUrlHint         = findViewById(R.id.tv_url_hint);
        tvQuality         = findViewById(R.id.tv_quality);
        tvFpsLabel        = findViewById(R.id.tv_fps_label);
        btnToggle         = findViewById(R.id.btn_toggle);
        seekBarQuality    = findViewById(R.id.seekbar_quality);
        switchAudio       = findViewById(R.id.switch_audio);
        switchAutoRestart = findViewById(R.id.switch_auto_restart);
        spinnerSampleRate = findViewById(R.id.spinner_sample_rate);
        spinnerChannels   = findViewById(R.id.spinner_channels);
        spinnerEncoding   = findViewById(R.id.spinner_encoding);
        editPort          = findViewById(R.id.edit_port);
        btnFps5           = findViewById(R.id.btn_fps_5);
        btnFps10          = findViewById(R.id.btn_fps_10);
        btnFps15          = findViewById(R.id.btn_fps_15);
        btnFps24          = findViewById(R.id.btn_fps_24);
        btnFps30          = findViewById(R.id.btn_fps_30);
        btnFps60          = findViewById(R.id.btn_fps_60);
        spinnerAuthMode        = findViewById(R.id.spinner_auth_mode);
        spinnerAuthModeSimple = findViewById(R.id.spinner_auth_mode_simple);
        btnIpDropdown         = findViewById(R.id.btn_ip_dropdown);
        layoutPinRow          = findViewById(R.id.layout_pin_row);
        layoutPinRowSimple    = findViewById(R.id.layout_pin_row_simple);
        layoutBasicRow        = findViewById(R.id.layout_basic_row);
        layoutBasicRowSimple  = findViewById(R.id.layout_basic_row_simple);
        tvPin                 = findViewById(R.id.tv_pin);
        tvPinSimple           = findViewById(R.id.tv_pin_simple);
        btnQrCode             = findViewById(R.id.btn_qr_code);
        btnPinRegen           = findViewById(R.id.btn_pin_regen);
        btnPinRegenSimple     = findViewById(R.id.btn_pin_regen_simple);
        btnBasicRegen         = findViewById(R.id.btn_basic_regen);
        btnBasicRegenSimple   = findViewById(R.id.btn_basic_regen_simple);
        editBasicUser         = findViewById(R.id.edit_basic_user);
        editBasicUserSimple   = findViewById(R.id.edit_basic_user_simple);
        editBasicPass         = findViewById(R.id.edit_basic_pass);
        editBasicPassSimple   = findViewById(R.id.edit_basic_pass_simple);
        switchHttps            = findViewById(R.id.switch_https);
        switchKeepAwake    = findViewById(R.id.switch_keep_awake);
        btnTabSimple       = findViewById(R.id.btn_tab_simple);
        btnTabAdvanced     = findViewById(R.id.btn_tab_advanced);
        layoutSimple       = findViewById(R.id.layout_simple);
        layoutAdvanced     = findViewById(R.id.layout_advanced);
        btnPresetPres      = findViewById(R.id.btn_preset_presentation);
        btnPresetGaming    = findViewById(R.id.btn_preset_gaming);
        btnPresetDashboard = findViewById(R.id.btn_preset_dashboard);
        btnPresetLowBW     = findViewById(R.id.btn_preset_low_bandwidth);
        btnIpDropdown      = findViewById(R.id.btn_ip_dropdown);
        btnQrCode          = findViewById(R.id.btn_qr_code);
        spinnerResolution    = findViewById(R.id.spinner_resolution);
        spinnerBitrate       = findViewById(R.id.spinner_bitrate);
        spinnerResolutionAdv = findViewById(R.id.spinner_resolution_adv);
        spinnerBitrateAdv    = findViewById(R.id.spinner_bitrate_adv);

        setupQuality();
        setupFps();
        setupAudio();
        setupSpinners();
        setupResolution();
        setupBitrate();
        setupAdvancedFps();
        setupPort();
        setupAutoRestart();
        setupAuth();
        setupHttps();
        setupKeepAwake();
        setupIps();

        // Tab switching
        btnTabSimple.setOnClickListener(v -> {
            layoutSimple.setVisibility(View.VISIBLE);
            layoutAdvanced.setVisibility(View.GONE);
            btnTabSimple.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPrimary)));
            btnTabSimple.setTextColor(Color.WHITE);
            btnTabAdvanced.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1e1e2e")));
            btnTabAdvanced.setTextColor(Color.parseColor("#e2e2f0"));
        });
        btnTabAdvanced.setOnClickListener(v -> {
            layoutSimple.setVisibility(View.GONE);
            layoutAdvanced.setVisibility(View.VISIBLE);
            btnTabAdvanced.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPrimary)));
            btnTabAdvanced.setTextColor(Color.WHITE);
            btnTabSimple.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1e1e2e")));
            btnTabSimple.setTextColor(Color.parseColor("#e2e2f0"));
        });

        // Preset buttons
        btnPresetPres.setOnClickListener(v -> applyPreset(Preset.PRESENTATION));
        btnPresetGaming.setOnClickListener(v -> applyPreset(Preset.GAMING));
        btnPresetDashboard.setOnClickListener(v -> applyPreset(Preset.DASHBOARD));
        btnPresetLowBW.setOnClickListener(v -> applyPreset(Preset.LOW_BANDWIDTH));

        btnToggle.setOnClickListener(v -> { if (isStreaming) stopStreaming(); else requestCapturePermission(); });
        tvUrl.setOnClickListener(v -> shareUrl());
        tvUrl.setOnLongClickListener(v -> { openInBrowser(); return true; });
        btnQrCode.setOnClickListener(v -> showQrCode());
    }

    private void setupQuality() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            seekBarQuality.setMin(10);
        }
        seekBarQuality.setMax(100);
        int savedQuality = settings.getInt(Settings.KEY_QUALITY, 70);
        seekBarQuality.setProgress(savedQuality);
        tvQuality.setText(savedQuality + "%");
        seekBarQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                int clamped = Math.max(10, p);
                tvQuality.setText(clamped + "%");
                MiroirService.setJpegQuality(clamped);
                settings.putInt(Settings.KEY_QUALITY, clamped);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void setupFps() {
        View.OnClickListener click = v -> {
            if      (v == btnFps5)  setFps(5);
            else if (v == btnFps10) setFps(10);
            else if (v == btnFps15) setFps(15);
            else if (v == btnFps24) setFps(24);
            else if (v == btnFps30) setFps(30);
            else if (v == btnFps60) setFps(60);
        };
        for (Button b : new Button[]{btnFps5, btnFps10, btnFps15, btnFps24, btnFps30, btnFps60})
            b.setOnClickListener(click);
        setFps(settings.getInt(Settings.KEY_FPS, 24));
    }

    private void setFps(int fps) {
        selectedFps = fps;
        MiroirService.setTargetFps(fps);
        settings.putInt(Settings.KEY_FPS, fps);
        tvFpsLabel.setText("FPS: " + fps);
        int active   = getColor(R.color.colorPrimary);
        int inactive = getColor(R.color.btnInactive);
        btnFps5.setBackgroundColor(fps == 5   ? active : inactive);
        btnFps10.setBackgroundColor(fps == 10 ? active : inactive);
        btnFps15.setBackgroundColor(fps == 15 ? active : inactive);
        btnFps24.setBackgroundColor(fps == 24 ? active : inactive);
        btnFps30.setBackgroundColor(fps == 30 ? active : inactive);
        btnFps60.setBackgroundColor(fps == 60 ? active : inactive);
    }

    private void setupAudio() {
        audioEnabled = settings.getBoolean(Settings.KEY_AUDIO_ENABLED, true);
        switchAudio.setChecked(audioEnabled);
        MiroirService.setAudioEnabled(audioEnabled);
        switchAudio.setOnCheckedChangeListener((btn, checked) -> {
            audioEnabled = checked;
            MiroirService.setAudioEnabled(checked);
            settings.putBoolean(Settings.KEY_AUDIO_ENABLED, checked);
        });
    }

    private void setupHttps() {
        // Always starts on plain HTTP: HTTPS mode depends on the device's AndroidKeyStore/
        // Keymint TLS support, which varies enough across OEMs and Android versions that
        // it isn't safe to silently carry an "on" choice into a new session. Not read
        // from or written to settings, so it's opt-in fresh every time the app opens.
        httpsEnabled = false;
        switchHttps.setChecked(false);
        MiroirService.setHttpsEnabled(false);
        switchHttps.setOnCheckedChangeListener((btn, checked) -> {
            httpsEnabled = checked;
            MiroirService.setHttpsEnabled(checked);
        });
    }

    private void setupKeepAwake() {
        keepAwake = settings.getBoolean(Settings.KEY_KEEP_AWAKE, true);
        switchKeepAwake.setChecked(keepAwake);
        switchKeepAwake.setOnCheckedChangeListener((btn, checked) -> {
            keepAwake = checked;
            settings.putBoolean(Settings.KEY_KEEP_AWAKE, checked);
        });
    }

    private void setupIps() {
        List<String> allIps = getAllLocalIps();
        if (allIps.isEmpty()) {
            btnIpDropdown.setVisibility(View.GONE);
            return;
        }
        btnIpDropdown.setVisibility(View.VISIBLE);

        // Show popup menu on dropdown button click
        btnIpDropdown.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, v);
            for (int i = 0; i < allIps.size(); i++) {
                String ip = allIps.get(i);
                String protocol = httpsEnabled ? "https" : "http";
                String displayText = protocol + "://" + ip + ":" + selectedPort;
                if (ip.equals(getLocalIpAddress())) {
                    displayText += " (current)";
                }
                popup.getMenu().add(0, i, i, displayText);
            }
            popup.setOnMenuItemClickListener(item -> {
                String selectedIp = allIps.get(item.getItemId());
                updateUrlDisplay(selectedIp);
                return true;
            });
            popup.show();
        });

        // QR code button
        btnQrCode.setOnClickListener(v -> showQrCodeDialog(getLocalIpAddress()));
    }

    private List<String> getAllLocalIps() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String ifaceName = iface.getName().toLowerCase(Locale.ROOT);
                if (ifaceName.contains("tun") || ifaceName.contains("tap") ||
                    ifaceName.contains("vpn") || ifaceName.contains("ppp") ||
                    ifaceName.contains("docker") || ifaceName.contains("veth") ||
                    ifaceName.equals("lo") || ifaceName.startsWith("lo:")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    String host = addr.getHostAddress();
                    if (addr.isLoopbackAddress() || host == null || host.contains(":")) continue;
                    if (!ips.contains(host)) ips.add(host);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "IP error", e);
        }
        return ips;
    }

    private void updateUrlDisplay(String ip) {
        String protocol = httpsEnabled ? "https" : "http";
        String url = protocol + "://" + ip + ":" + selectedPort;
        if (authMode == MiroirService.AuthMode.PIN) {
            url += "/?pin=" + currentPin;
        }
        currentUrl = url;
        tvUrl.setText(currentUrl);
        tvUrl.setVisibility(View.VISIBLE);
        tvUrlLabel.setVisibility(View.VISIBLE);
        tvUrlHint.setVisibility(View.VISIBLE);
        btnQrCode.setVisibility(View.VISIBLE);
        btnIpDropdown.setVisibility(View.VISIBLE);
    }

    private void showQrCode() {
        if (currentUrl == null || currentUrl.isEmpty()) return;
        showQrCodeDialog(currentUrl);
    }

    private void showQrCodeDialog(String url) {
        try {
            com.google.zxing.Writer writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix bitMatrix = writer.encode(
                url, com.google.zxing.BarcodeFormat.QR_CODE, 400, 400);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("QR Code");
            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(bitmap);
            imageView.setPadding(40, 20, 40, 20);
            builder.setView(imageView);
            builder.setPositiveButton("Share", (d, w) -> {
                // Save bitmap to file and share
                try {
                    File cacheDir = getCacheDir();
                    File file = File.createTempFile("miroir_qr_", ".png", cacheDir);
                    FileOutputStream fos = new FileOutputStream(file);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("image/png");
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "Share QR Code"));
                } catch (Exception e) {
                    Toast.makeText(this, "Failed to share QR code", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("Close", null);
            builder.show();
        } catch (Exception e) {
            Log.e(TAG, "QR code generation failed", e);
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show();
        }
    }

    private String getLocalIpAddress() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String ifaceName = iface.getName().toLowerCase(Locale.ROOT);
                if (ifaceName.contains("tun") || ifaceName.contains("tap") ||
                    ifaceName.contains("vpn") || ifaceName.contains("ppp") ||
                    ifaceName.contains("docker") || ifaceName.contains("veth") ||
                    ifaceName.equals("lo") || ifaceName.startsWith("lo:")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    String host = addr.getHostAddress();
                    if (addr.isLoopbackAddress() || host == null || host.contains(":")) continue;
                    if (ifaceName.startsWith("wlan") || ifaceName.startsWith("wifi") ||
                        ifaceName.equals("wlan0") || ifaceName.equals("wlan1")) {
                        return host;
                    }
                }
            }
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String ifaceName = iface.getName().toLowerCase(Locale.ROOT);
                if (ifaceName.contains("tun") || ifaceName.contains("tap") ||
                    ifaceName.contains("vpn") || ifaceName.contains("ppp") ||
                    ifaceName.contains("docker") || ifaceName.contains("veth") ||
                    ifaceName.equals("lo") || ifaceName.startsWith("lo:")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    String host = addr.getHostAddress();
                    if (addr.isLoopbackAddress() || host == null || host.contains(":")) continue;
                    if (ifaceName.startsWith("eth") || ifaceName.startsWith("enp") ||
                        ifaceName.startsWith("rmnet") || ifaceName.startsWith("ccmni")) {
                        return host;
                    }
                }
            }
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String ifaceName = iface.getName().toLowerCase(Locale.ROOT);
                if (ifaceName.contains("tun") || ifaceName.contains("tap") ||
                    ifaceName.contains("vpn") || ifaceName.contains("ppp") ||
                    ifaceName.contains("docker") || ifaceName.contains("veth") ||
                    ifaceName.equals("lo") || ifaceName.startsWith("lo:")) {
                    continue;
                }
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    String host = addr.getHostAddress();
                    if (addr.isLoopbackAddress() || host == null || host.contains(":")) continue;
                    return host;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "IP error", e);
        }
        return "0.0.0.0";
    }

    private void setupSpinners() {

        String[] srLabels = {"16 kHz (low bandwidth)", "22 kHz (FM quality)",
                             "44.1 kHz (CD quality)",  "48 kHz (studio)"};
        int[]    srValues = {16000, 22050, 44100, 48000};

        ArrayAdapter<String> srAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, srLabels);
        srAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSampleRate.setAdapter(srAdapter);
        selectedSampleRate = settings.getInt(Settings.KEY_AUDIO_SAMPLE_RATE, 44100);
        spinnerSampleRate.setSelection(indexOf(srValues, selectedSampleRate, 2));
        MiroirService.setAudioSampleRate(selectedSampleRate);
        spinnerSampleRate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedSampleRate = srValues[pos];
                MiroirService.setAudioSampleRate(selectedSampleRate);
                settings.putInt(Settings.KEY_AUDIO_SAMPLE_RATE, selectedSampleRate);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        String[] chLabels = {"Mono", "Stereo"};
        int[]    chValues = {1, 2};

        ArrayAdapter<String> chAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, chLabels);
        chAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerChannels.setAdapter(chAdapter);
        selectedChannels = settings.getInt(Settings.KEY_AUDIO_CHANNELS, 2);
        spinnerChannels.setSelection(indexOf(chValues, selectedChannels, 1));
        MiroirService.setAudioChannels(selectedChannels);
        spinnerChannels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedChannels = chValues[pos];
                MiroirService.setAudioChannels(selectedChannels);
                settings.putInt(Settings.KEY_AUDIO_CHANNELS, selectedChannels);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        String[] encLabels = {"PCM 16-bit (standard)", "PCM 8-bit (small)", "PCM Float (high quality)"};
        int[]    encValues = {
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_FLOAT
        };

        ArrayAdapter<String> encAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, encLabels);
        encAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerEncoding.setAdapter(encAdapter);
        selectedEncoding = settings.getInt(Settings.KEY_AUDIO_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        spinnerEncoding.setSelection(indexOf(encValues, selectedEncoding, 0));
        MiroirService.setAudioEncoding(selectedEncoding);
        spinnerEncoding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedEncoding = encValues[pos];
                MiroirService.setAudioEncoding(selectedEncoding);
                settings.putInt(Settings.KEY_AUDIO_ENCODING, selectedEncoding);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private static int indexOf(int[] values, int target, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == target) return i;
        }
        return fallback;
    }

    private void setupResolution() {
        String[] resLabels = {"854x480 (480p)", "1280x720 (720p)", "1920x1080 (1080p)"};
        int[][] resValues = {{854, 480}, {1280, 720}, {1920, 1080}};

        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, resLabels);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerResolution.setAdapter(resAdapter);
        spinnerResolutionAdv.setAdapter(resAdapter);

        int savedW = settings.getInt(Settings.KEY_MAX_WIDTH, 1280);
        int savedH = settings.getInt(Settings.KEY_MAX_HEIGHT, 720);
        int idx = indexOfResolution(resValues, savedW, savedH, 1);
        spinnerResolution.setSelection(idx);
        spinnerResolutionAdv.setSelection(idx);

        AdapterView.OnItemSelectedListener resListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                maxWidthPref = resValues[pos][0];
                maxHeightPref = resValues[pos][1];
                settings.putInt(Settings.KEY_MAX_WIDTH, maxWidthPref);
                settings.putInt(Settings.KEY_MAX_HEIGHT, maxHeightPref);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        spinnerResolution.setOnItemSelectedListener(resListener);
        spinnerResolutionAdv.setOnItemSelectedListener(resListener);
    }

    private int indexOfResolution(int[][] values, int w, int h, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i][0] == w && values[i][1] == h) return i;
        }
        return fallback;
    }

    private void setupBitrate() {
        String[] brLabels = {"1000 kbps (low)", "2000 kbps (med)", "4000 kbps (high)", "6000 kbps (very high)", "8000 kbps (max)"};
        int[] brValues = {1000, 2000, 4000, 6000, 8000};

        ArrayAdapter<String> brAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, brLabels);
        brAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBitrate.setAdapter(brAdapter);
        spinnerBitrateAdv.setAdapter(brAdapter);

        int savedB = settings.getInt(Settings.KEY_BITRATE, 4000);
        int idx = indexOf(brValues, savedB, 2);
        spinnerBitrate.setSelection(idx);
        spinnerBitrateAdv.setSelection(idx);

        AdapterView.OnItemSelectedListener brListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                bitratePref = brValues[pos];
                settings.putInt(Settings.KEY_BITRATE, bitratePref);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        spinnerBitrate.setOnItemSelectedListener(brListener);
        spinnerBitrateAdv.setOnItemSelectedListener(brListener);
    }

    private void setupAdvancedFps() {
        String[] fpsLabels = {"5 fps", "10 fps", "15 fps", "24 fps", "30 fps", "60 fps"};
        int[]    fpsValues = {5, 10, 15, 24, 30, 60};

        ArrayAdapter<String> fpsAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, fpsLabels);
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        Spinner spinnerFpsAdvanced = findViewById(R.id.spinner_fps_advanced);
        spinnerFpsAdvanced.setAdapter(fpsAdapter);

        selectedFps = settings.getInt(Settings.KEY_FPS, 24);
        spinnerFpsAdvanced.setSelection(indexOf(fpsValues, selectedFps, 3));

        spinnerFpsAdvanced.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedFps = fpsValues[pos];
                MiroirService.setTargetFps(selectedFps);
                settings.putInt(Settings.KEY_FPS, selectedFps);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void setupPort() {
        selectedPort = settings.getInt(Settings.KEY_PORT, DEFAULT_PORT);
        editPort.setText(String.valueOf(selectedPort));
        editPort.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) validateAndApplyPort();
        });
        editPort.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                validateAndApplyPort();
                editPort.clearFocus();
            }
            return false;
        });
    }

    private void setupAutoRestart() {
        autoRestart = settings.getBoolean(Settings.KEY_AUTO_RESTART, false);
        switchAutoRestart.setChecked(autoRestart);
        switchAutoRestart.setOnCheckedChangeListener((btn, checked) -> {
            autoRestart = checked;
            settings.putBoolean(Settings.KEY_AUTO_RESTART, checked);
        });
    }

    private void setupAuth() {
        authMode = parseAuthModeName(settings.getString(Settings.KEY_AUTH_MODE, MiroirService.AuthMode.NONE.name()));

        currentPin = settings.getString(Settings.KEY_PIN, null);
        if (currentPin == null) {
            currentPin = generatePin();
            settings.putString(Settings.KEY_PIN, currentPin);
        }
        basicUser = settings.getString(Settings.KEY_BASIC_USER, "viewer");
        basicPass = settings.getString(Settings.KEY_BASIC_PASS, null);
        if (basicPass == null) {
            basicPass = generatePassword();
            settings.putString(Settings.KEY_BASIC_PASS, basicPass);
        }

        String[] labels = {"None", "PIN", "Basic Auth"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAuthMode.setAdapter(adapter);
        spinnerAuthMode.setSelection(authModeToIndex(authMode));
        tvPin.setText(currentPin);
        editBasicUser.setText(basicUser);
        editBasicPass.setText(basicPass);
        updateAuthRowsVisibility();

        spinnerAuthMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                authMode = indexToAuthMode(pos);
                settings.putString(Settings.KEY_AUTH_MODE, authMode.name());
                updateAuthRowsVisibility();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        btnPinRegen.setOnClickListener(v -> {
            currentPin = generatePin();
            settings.putString(Settings.KEY_PIN, currentPin);
            tvPin.setText(currentPin);
        });
        btnBasicRegen.setOnClickListener(v -> {
            basicPass = generatePassword();
            settings.putString(Settings.KEY_BASIC_PASS, basicPass);
            editBasicPass.setText(basicPass);
        });
        editBasicUser.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                basicUser = editBasicUser.getText().toString();
                settings.putString(Settings.KEY_BASIC_USER, basicUser);
            }
        });
        editBasicPass.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                basicPass = editBasicPass.getText().toString();
                settings.putString(Settings.KEY_BASIC_PASS, basicPass);
            }
        });
    }

    private static MiroirService.AuthMode parseAuthModeName(String name) {
        try {
            return MiroirService.AuthMode.valueOf(name);
        } catch (Exception e) {
            return MiroirService.AuthMode.NONE;
        }
    }

    private void updateAuthRowsVisibility() {
        layoutPinRow.setVisibility(authMode == MiroirService.AuthMode.PIN ? View.VISIBLE : View.GONE);
        layoutBasicRow.setVisibility(authMode == MiroirService.AuthMode.BASIC ? View.VISIBLE : View.GONE);
    }

    private static int authModeToIndex(MiroirService.AuthMode mode) {
        switch (mode) {
            case PIN:   return 1;
            case BASIC: return 2;
            default:    return 0;
        }
    }

    private static MiroirService.AuthMode indexToAuthMode(int pos) {
        switch (pos) {
            case 1:  return MiroirService.AuthMode.PIN;
            case 2:  return MiroirService.AuthMode.BASIC;
            default: return MiroirService.AuthMode.NONE;
        }
    }

    private String generatePin() {
        return String.format(Locale.US, "%06d", secureRandom.nextInt(1_000_000));
    }

    private String generatePassword() {
        byte[] raw = new byte[9];
        secureRandom.nextBytes(raw);
        return android.util.Base64.encodeToString(raw,
            android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    private void validateAndApplyPort() {
        String text = editPort.getText().toString().trim();
        try {
            int port = Integer.parseInt(text);
            if (port < 1 || port > 65535) {
                Toast.makeText(this, "Port must be between 1 and 65535", Toast.LENGTH_SHORT).show();
                editPort.setText(String.valueOf(selectedPort));
            } else {
                selectedPort = port;
                settings.putInt(Settings.KEY_PORT, selectedPort);
            }
        } catch (NumberFormatException e) {
            editPort.setText(String.valueOf(selectedPort));
        }
    }

    private void shareUrl() {
        if (currentUrl.isEmpty()) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Watch my screen live: " + currentUrl);
        share.putExtra(Intent.EXTRA_SUBJECT, "Live screen stream");
        startActivity(Intent.createChooser(share, "Share stream URL"));
    }

    private void openInBrowser() {
        if (currentUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)));
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Stream URL", currentUrl));
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestCapturePermission() {
        validateAndApplyPort();
        if (audioEnabled && ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
        } else {
            launchCapturePermission();
        }
    }

    private void launchCapturePermission() {
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projectionLauncher.launch(mpm.createScreenCaptureIntent());
    }

    private void startStreamingService(int resultCode, Intent data) {
        Intent svc = new Intent(this, MiroirService.class);
        svc.setAction(MiroirService.ACTION_START);
        svc.putExtra(MiroirService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(MiroirService.EXTRA_DATA, data);
        svc.putExtra(MiroirService.EXTRA_PORT, selectedPort);
        svc.putExtra(MiroirService.EXTRA_QUALITY, seekBarQuality.getProgress());
        svc.putExtra(MiroirService.EXTRA_FPS, selectedFps);
        svc.putExtra(MiroirService.EXTRA_AUDIO, audioEnabled);
        svc.putExtra(MiroirService.EXTRA_HTTPS, httpsEnabled);
        svc.putExtra(MiroirService.EXTRA_KEEP_AWAKE, keepAwake);
        svc.putExtra(MiroirService.EXTRA_AUDIO_SR, selectedSampleRate);
        svc.putExtra(MiroirService.EXTRA_AUDIO_CH, selectedChannels);
        svc.putExtra(MiroirService.EXTRA_AUDIO_ENC, selectedEncoding);
        svc.putExtra(MiroirService.EXTRA_MAX_WIDTH, maxWidthPref);
        svc.putExtra(MiroirService.EXTRA_MAX_HEIGHT, maxHeightPref);
        svc.putExtra(MiroirService.EXTRA_BITRATE, bitratePref);
        svc.putExtra(MiroirService.EXTRA_AUTH_MODE, authMode.name());
        svc.putExtra(MiroirService.EXTRA_PIN, currentPin);
        svc.putExtra(MiroirService.EXTRA_BASIC_USER, basicUser);
        svc.putExtra(MiroirService.EXTRA_BASIC_PASS, basicPass);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
        isStreaming = true;
        updateStatusRunning();
    }

    private void stopStreaming() {
        Intent svc = new Intent(this, MiroirService.class);
        svc.setAction(MiroirService.ACTION_STOP);
        startService(svc);
        isStreaming = false;
        updateStatusStopped();
    }

    private void updateStatusRunning() {
        tvStatus.setText("● LIVE");
        tvStatus.setTextColor(getColor(R.color.green));
        btnToggle.setText("Stop Streaming");
        btnToggle.setBackgroundColor(getColor(R.color.red));
        currentUrl = (httpsEnabled ? "https://" : "http://") + getLocalIpAddress() + ":" + selectedPort;
        if (authMode == MiroirService.AuthMode.PIN) {
            currentUrl += "/?pin=" + currentPin;
        }
        updateUrlDisplay(getLocalIpAddress());
        setOptionsEnabled(false);
        if (keepAwake) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updateStatusStopped() {
        tvStatus.setText("○ STOPPED");
        tvStatus.setTextColor(getColor(R.color.gray));
        btnToggle.setText("Start Streaming");
        btnToggle.setBackgroundColor(getColor(R.color.colorPrimary));
        tvUrl.setVisibility(View.GONE);
        tvUrlLabel.setVisibility(View.GONE);
        tvUrlHint.setVisibility(View.GONE);
        btnIpDropdown.setVisibility(View.GONE);
        btnQrCode.setVisibility(View.GONE);
        currentUrl = "";
        setOptionsEnabled(true);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void setOptionsEnabled(boolean on) {
        for (Button b : new Button[]{btnFps5, btnFps10, btnFps15, btnFps24, btnFps30, btnFps60})
            b.setEnabled(on);
        switchAudio.setEnabled(on);
        switchHttps.setEnabled(on);
        switchKeepAwake.setEnabled(on);
        spinnerSampleRate.setEnabled(on);
        spinnerChannels.setEnabled(on);
        spinnerEncoding.setEnabled(on);
        editPort.setEnabled(on);
        switchAutoRestart.setEnabled(on);
        spinnerAuthMode.setEnabled(on);
        btnPinRegen.setEnabled(on);
        btnBasicRegen.setEnabled(on);
        editBasicUser.setEnabled(on);
        editBasicPass.setEnabled(on);
    }

    private void showErrorDialog(ErrorReporter.AppError error) {
        if (isFinishing() || isDestroyed()) return;
        String body = error.message + (error.detail != null && !error.detail.isEmpty()
            ? "\n\n" + error.detail.substring(0, Math.min(400, error.detail.length()))
            : "");
        String copyText = "[" + error.source.label + " / " + error.level.name() + "]\n" + body;
        new AlertDialog.Builder(this)
            .setTitle(error.source.label + " — " + error.level.name())
            .setMessage(body)
            .setPositiveButton("Dismiss", null)
            .setNegativeButton("Copy", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Miroir error", copyText));
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("Stop Stream", (d, w) -> { if (isStreaming) stopStreaming(); })
            .show();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // activity_main.xml has no orientation-specific variant, so there's nothing to
        // re-inflate here. Re-inflating on every rotation used to cause a visibly
        // double-rendered frame on lower-end devices, and could swallow the tap that was
        // meant to reach the "Start Streaming" button.
    }

    @Override
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(MiroirService.ACTION_STOPPED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stoppedReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // RECEIVER_NOT_EXPORTED constant added in API 26
            registerReceiver(stoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stoppedReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(stoppedReceiver); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isStreaming) stopStreaming();
    }
}
