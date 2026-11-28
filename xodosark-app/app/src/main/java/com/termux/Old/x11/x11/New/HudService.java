package com.termux.x11;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.termux.x11.controller.core.CPUStatus;
import com.termux.x11.controller.core.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HudService extends Service {

    private static final String TAG = "HudService";
    private volatile boolean hudVisible = false;

    /* ---------- ACTIVITY ATTACHMENT ---------- */
    private WeakReference<Activity> activityRef;
    private TextView hudView;
    private boolean attached = false;

    /* ---------- THREADING ---------- */
    private ScheduledExecutorService hudScheduler;
    private Handler mainHandler;

    /* ---------- FPS ---------- */
    private volatile String fpsText = "FPS: N/A";
    private volatile float fpsValue = -1f;
    private Thread fpsThread;
    private volatile boolean fpsRunning = true;

    /* ---------- GPU LOAD ---------- */
    private volatile String gpuLoadText = ""; // empty when not available

    /* ---------- MEM / TEMP / CPU FREQ ---------- */
    private String totalRam;
    private Process logcatProcess;

    /* ===================== SERVICE ===================== */

    public class LocalBinder extends Binder {
        public HudService getService() {
            return HudService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        totalRam = getTotalRam();
        startHudLoop();
    }

    /* ===================== ACTIVITY BINDING ===================== */

    public void attachToActivity(Activity activity) {
        detach();
        activityRef = new WeakReference<>(activity);

        mainHandler.post(() -> {
            Activity act = activityRef.get();
            if (act == null) return;

            hudView = new TextView(act);
            hudView.setTypeface(Typeface.MONOSPACE);
            hudView.setTextSize(12);
            hudView.setPadding(12, 6, 12, 6);
            hudView.setBackgroundColor(Color.argb(160, 0, 0, 0));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START
            );
            params.leftMargin = 8;
            params.topMargin = 0;

            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            decor.addView(hudView, params);
            attached = true;
            hudVisible = true;
            startFpsReader();
            Log.d(TAG, "HUD attached to activity");
        });
    }

    public void detach() {
        mainHandler.post(() -> {
            if (!attached || hudView == null) return;
            Activity act = activityRef != null ? activityRef.get() : null;
            if (act == null) return;

            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            decor.removeView(hudView);
            hudView = null;
            hudVisible = false;
            attached = false;
            Log.d(TAG, "HUD detached");
            stopFpsReader();
        });
    }

    /* ===================== HUD UPDATE LOOP ===================== */

    private void startHudLoop() {
        hudScheduler = Executors.newSingleThreadScheduledExecutor();
        hudScheduler.scheduleAtFixedRate(() -> {
            // Read GPU load – returns "" if N/A
            gpuLoadText = readGpuLoad();

            String fullText = buildHudText();
            SpannableString colored = colorizeText(fullText);

            mainHandler.post(() -> {
                if (attached && hudView != null) {
                    hudView.setText(colored);
                    adjustTextSizeToFit(hudView, colored.toString());
                }
            });
        }, 0, 2, TimeUnit.SECONDS);
    }

    /* ===================== BUILD HUD TEXT ===================== */

    private String buildHudText() {
        StringBuilder sb = new StringBuilder();

        // Always include FPS, CPU temp, CPU freq
        sb.append(fpsText);
        sb.append(" | ").append(getCpuTemp());
        sb.append(" | ").append(getCpuFreqPercent());

        // Only add GPU if we actually have a reading (non-empty)
        if (!gpuLoadText.isEmpty()) {
            sb.append(" | ").append(gpuLoadText);
        }

        sb.append(" | ").append(getMemoryInfo());
        return sb.toString();
    }

    private SpannableString colorizeText(String full) {
        SpannableString s = new SpannableString(full);

        // FPS coloring
        color(s, fpsText, fpsValue >= 0 && fpsValue < 10 ? Color.RED : Color.GREEN);

        // CPU temp coloring
        float tempVal = getCpuTempValue();
        color(s, getCpuTemp(), tempColor(tempVal));

        // CPU freq coloring
        int cpuFreqPercent = getCpuFreqRawPercent();
        color(s, getCpuFreqPercent(), cpuFreqPercent < 20 ? Color.RED :
                (cpuFreqPercent < 50 ? Color.YELLOW : Color.GREEN));

        // GPU load coloring (if visible)
        if (!gpuLoadText.isEmpty()) {
            int gpuPercent = getGpuLoadValue();
            color(s, gpuLoadText, gpuPercent < 0 ? Color.LTGRAY :
                    (gpuPercent > 80 ? Color.RED :
                     (gpuPercent > 50 ? Color.rgb(255, 165, 0) : Color.CYAN)));
        }

        // Memory coloring
        long availMB = getAvailableMemoryMB();
        color(s, getMemoryInfo(), (availMB >= 0 && availMB < 800) ? Color.RED : Color.CYAN);

        return s;
    }

    private void color(SpannableString s, String part, int color) {
        int start = s.toString().indexOf(part);
        if (start >= 0) {
            s.setSpan(new ForegroundColorSpan(color),
                    start, start + part.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private int tempColor(float tempVal) {
        if (tempVal < 0) return Color.LTGRAY;
        else if (tempVal > 70) return Color.RED;
        else if (tempVal > 40) return Color.rgb(255, 165, 0);
        else return Color.CYAN;
    }

    private void adjustTextSizeToFit(TextView textView, String text) {
        if (textView == null || text == null) return;

        Activity act = activityRef != null ? activityRef.get() : null;
        if (act == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        act.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int screenWidth = metrics.widthPixels;
        int availableWidth = screenWidth - textView.getPaddingLeft() - textView.getPaddingRight() - 16;

        Paint paint = new Paint();
        paint.setTypeface(textView.getTypeface());
        paint.setTextSize(textView.getTextSize());

        float textWidth = paint.measureText(text);
        if (textWidth <= availableWidth) return;

        float minSizePx = 8 * metrics.density;
        float newSizePx = textView.getTextSize();
        while (newSizePx > minSizePx && textWidth > availableWidth) {
            newSizePx -= 1;
            paint.setTextSize(newSizePx);
            textWidth = paint.measureText(text);
        }
        textView.setTextSize(newSizePx / metrics.density);
    }

    /* ===================== FPS READER ===================== */

    public void stopFpsReader() {
        fpsRunning = false;
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (fpsThread != null && fpsThread.isAlive()) {
            fpsThread.interrupt();
            fpsThread = null;
        }
    }

    private void startFpsReader() {
        if (!hudVisible) return;
        if (fpsThread != null && fpsThread.isAlive()) return;
        fpsRunning = true;
        fpsThread = new Thread(() -> {
            try {
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();
                ProcessBuilder pb = new ProcessBuilder(
                        "logcat", "-s", "LorieNative:I", "-v", "brief"
                );
                pb.redirectErrorStream(true);
                logcatProcess = pb.start();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(logcatProcess.getInputStream()));
                String line;
                while (fpsRunning && (line = br.readLine()) != null) {
                    if (!hudVisible) {
                        stopFpsReader();
                        break;
                    }
                    if (line.contains("FPS")) parseFps(line);
                }
            } catch (Exception e) {
                Log.e(TAG, "FPS reader error", e);
            }
        }, "FPS-Reader");
        fpsThread.setDaemon(true);
        fpsThread.start();
    }

    private void parseFps(String line) {
        int idx = line.lastIndexOf('=');
        if (idx < 0) return;

        String num = line.substring(idx + 1).replace("FPS", "").trim();
        try {
            fpsValue = Float.parseFloat(num);
            fpsText = "FPS: " + num;
        } catch (Exception ignored) {}
    }

    /* ===================== CPU FREQUENCY PERCENTAGE ===================== */

    private String getCpuFreqPercent() {
        int percent = getCpuFreqRawPercent();
        if (percent < 0) return "CPUf: N/A";
        return "CPUf: " + percent + "%";
    }

    private int getCpuFreqRawPercent() {
        try {
            short[] current = CPUStatus.getCurrentClockSpeeds();
            if (current == null || current.length == 0) return -1;

            int totalCurrent = 0;
            int totalMax = 0;
            for (int i = 0; i < current.length; i++) {
                short max = CPUStatus.getMaxClockSpeed(i);
                if (max <= 0) return -1;
                totalCurrent += current[i];
                totalMax += max;
            }
            return (int) ((totalCurrent * 100L) / totalMax);
        } catch (Exception e) {
            Log.e(TAG, "Failed to read CPU frequencies", e);
            return -1;
        }
    }

    /* ===================== CPU TEMPERATURE ===================== */

    private String getCpuTemp() {
        for (int i = 0; i < 10; i++) {
            try {
                String path = "/sys/class/thermal/thermal_zone" + i + "/temp";
                BufferedReader br = new BufferedReader(new FileReader(path));
                int temp = Integer.parseInt(br.readLine().trim());
                br.close();

                if (temp > 10000) {
                    return String.format("CPU: %.1f°C", temp / 1000f);
                }
            } catch (Exception ignored) {}
        }
        return "CPU: N/A";
    }

    private float getCpuTempValue() {
        for (int i = 0; i < 10; i++) {
            try {
                String path = "/sys/class/thermal/thermal_zone" + i + "/temp";
                BufferedReader br = new BufferedReader(new FileReader(path));
                int temp = Integer.parseInt(br.readLine().trim());
                br.close();

                if (temp > 10000) {
                    return temp / 1000f;
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /* ===================== MEMORY ===================== */

    private String getMemoryInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        return "MEM: " + StringUtils.formatBytes(used, false) + " / " + totalRam;
    }

    private String getTotalRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return StringUtils.formatBytes(mi.totalMem, false);
    }

    private long getAvailableMemoryMB() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem / (1024 * 1024);
        } catch (Exception e) {
            return -1;
        }
    }

    /* ===================== UNIVERSAL MULTI-VENDOR GPU LOAD SCANNER ===================== */

    /**
     * Tries all known GPU utilization sysfs paths.
     * Returns "GPU: X%" if load found, otherwise an empty string (which hides the column).
     */
    private String readGpuLoad() {
        // 1. Array of known world-readable GPU utilization files with parser hints
        String[][] vendorPaths = {
            // Qualcomm Adreno
            {"/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "QUALCOMM_BUSY"},
            {"/sys/class/kgsl/kgsl-3d0/gpubusy", "QUALCOMM_TICKS"},

            // ARM Mali (various paths)
            {"/sys/class/misc/mali0/device/utilization", "MALI"},
            {"/sys/class/misc/mali0/device/utilisation", "MALI"},
            {"/sys/module/mali/parameters/mali_gpu_utilization", "MALI"},
            {"/sys/devices/platform/11800000.mali/utilization", "MALI"},

            // PowerVR / IMG
            {"/sys/dgpu/power_status", "POWERVR"},
            {"/sys/class/powervr/utilization", "POWERVR"}
        };

        for (String[] target : vendorPaths) {
            String path = target[0];
            String engineType = target[1];

            File file = new File(path);
            if (file.exists() && file.canRead()) {
                String value = readRawFileNode(file, engineType);
                if (value != null) {
                    return "GPU: " + value + "%";
                }
            }
        }

        // No readable node → return empty to hide the column
        return "";
    }

    /**
     * Parses a raw GPU load file according to the vendor type.
     * Returns the percentage as a clean integer string, or null if unparsable.
     */
    private String readRawFileNode(File file, String engineType) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            String line = br.readLine();
            if (line == null) return null;

            String trimmed = line.trim();

            switch (engineType) {
                case "QUALCOMM_BUSY":
                    // Direct percentage, e.g. "42" or "42 %"
                    trimmed = trimmed.replace("%", "").trim();
                    Integer.parseInt(trimmed); // validate numeric
                    return trimmed;

                case "QUALCOMM_TICKS":
                    // Legacy format: "busy_ticks total_ticks"
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length >= 2) {
                        long busy = Long.parseLong(parts[0]);
                        long total = Long.parseLong(parts[1]);
                        if (total > 0) return String.valueOf((busy * 100) / total);
                    }
                    break;

                case "MALI":
                    // Mali nodes may output raw ints, possibly scaled (0-255 or 0-1000)
                    trimmed = trimmed.replace("%", "").trim();
                    int rawMali = Integer.parseInt(trimmed);
                    if (rawMali > 100) {
                        // Probably not a direct percentage
                        if (rawMali <= 255) return String.valueOf((rawMali * 100) / 255);
                        if (rawMali <= 1000) return String.valueOf(rawMali / 10);
                        // Fallback: treat as percentage if >1000 (unlikely)
                    }
                    // <= 100: assume it's a straight percentage
                    return String.valueOf(rawMali);

                case "POWERVR":
                    // Might contain non-numeric status strings like "on" – reject if not numeric
                    Integer.parseInt(trimmed);
                    return trimmed;
            }
        } catch (Exception ignored) {
            // parsing failed, try next path
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Extracts the numeric GPU load percentage for coloring, or -1 if hidden/N/A.
     */
    private int getGpuLoadValue() {
        try {
            if (gpuLoadText == null || gpuLoadText.isEmpty()) {
                return -1;
            }
            String clean = gpuLoadText.replace("GPU:", "").replace("%", "").trim();
            return Integer.parseInt(clean);
        } catch (Exception e) {
            return -1;
        }
    }

    /* ===================== CLEANUP ===================== */

    @Override
    public void onDestroy() {
        fpsRunning = false;
        detach();
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (hudScheduler != null) hudScheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}