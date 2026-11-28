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
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;

import com.termux.x11.controller.core.CPUStatus;      // for CPU frequency
import com.termux.x11.controller.core.StringUtils;    // for memory formatting
import com.termux.x11.controller.winhandler.TaskManagerDialog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HUD service that shows FPS, CPU temp, CPU frequency %, GPU info, and memory.
 * GPU info is read from /sdcard/gpuinfo (written by the Linux container).
 * Falls back to Android system properties if the file is unavailable.
 */
public class HudService extends Service {

    private static final String TAG = "HudService";
    private static final String GPU_INFO_FILE = "/sdcard/gpuinfo";
private volatile boolean hudVisible = false;   // tracks if HUD is attached
    /* ---------- ACTIVITY ATTACHMENT ---------- */
    private WeakReference<Activity> activityRef;
    private TextView hudView;
    private boolean attached = false;

    /* ---------- THREADING ---------- */
    private ScheduledExecutorService hudScheduler;
    private ScheduledExecutorService gpuScheduler;
    private Handler mainHandler;

    /* ---------- FPS ---------- */
    private volatile String fpsText = "FPS: N/A";
    private volatile float fpsValue = -1f;
    private Thread fpsThread;
    private volatile boolean fpsRunning = true;

    /* ---------- GPU (simplified) ---------- */
    private volatile String openGLRenderer = "OGL: ...";
    private volatile String vulkanDeviceName = "VK: ...";

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
     //   startFpsReader();
        startGpuInfoFetcher();
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

            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(
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
        stopFpsReader();   // kill logcat process immediately
    });
}

    /* ===================== HUD UPDATE LOOP ===================== */

    private void startHudLoop() {
        hudScheduler = Executors.newSingleThreadScheduledExecutor();
        hudScheduler.scheduleAtFixedRate(() -> {
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
        String fps = fpsText;
        String temp = getCpuTemp();
        String cpuFreq = getCpuFreqPercent();          // now uses CPUStatus
        String mem = getMemoryInfo();                   // uses StringUtils internally

        return fps + " | " + temp + " | " + cpuFreq + " | " +
               openGLRenderer + " | " + vulkanDeviceName + " | " + mem;
    }

    private SpannableString colorizeText(String full) {
        SpannableString s = new SpannableString(full);

        color(s, fpsText, fpsValue >= 0 && fpsValue < 10 ? Color.RED : Color.GREEN);

        float tempVal = getCpuTempValue();
        color(s, getCpuTemp(), tempColor(tempVal));

        // Color CPU frequency based on percentage
        int cpuFreqPercent = getCpuFreqRawPercent();
        color(s, getCpuFreqPercent(), cpuFreqPercent < 20 ? Color.RED :
                (cpuFreqPercent < 50 ? Color.YELLOW : Color.GREEN));

        color(s, openGLRenderer, Color.MAGENTA);
        color(s, vulkanDeviceName, Color.MAGENTA);

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
            // AUTO STOP CONDITION
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

    /* ===================== CPU FREQUENCY PERCENTAGE (matches task manager) ===================== */

    /**
     * Returns a string like "CPUf: 45%" based on average current frequency / max frequency.
     */
    private String getCpuFreqPercent() {
        int percent = getCpuFreqRawPercent();
        if (percent < 0) return "CPUf: N/A";
        return "CPUf: " + percent + "%";
    }

    /**
     * Returns raw percentage (0–100) or -1 if unable to read.
     */
    private int getCpuFreqRawPercent() {
        try {
            short[] current = CPUStatus.getCurrentClockSpeeds();
            if (current == null || current.length == 0) return -1;

            int totalCurrent = 0;
            int totalMax = 0;
            for (int i = 0; i < current.length; i++) {
                short max = CPUStatus.getMaxClockSpeed(i);
                if (max <= 0) return -1;   // invalid max
                totalCurrent += current[i];
                totalMax += max;
            }
            // average percentage = (totalCurrent / totalMax) * 100
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

    /* ===================== MEMORY (using StringUtils) ===================== */

    private String getMemoryInfo() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        // Use StringUtils.formatBytes with shortFormat = false (like task manager)
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

    /* ===================== GPU INFO FETCHER (unchanged) ===================== */

    private void startGpuInfoFetcher() {
        gpuScheduler = Executors.newSingleThreadScheduledExecutor();
        gpuScheduler.scheduleAtFixedRate(this::fetchGpuInfo, 0, 5, TimeUnit.SECONDS);
    }

    private void fetchGpuInfo() {
        if (readGpuInfoFromFile()) {
            return;
        }
        fallbackGpuInfo();
    }

    private boolean readGpuInfoFromFile() {
        File file = new File(GPU_INFO_FILE);
        if (!file.exists()) return false;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            String ogl = null;
            String vk = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("OGL=")) {
                    ogl = line.substring(4).trim();
                } else if (line.startsWith("VK=")) {
                    vk = line.substring(3).trim();
                }
            }
            boolean updated = false;
            if (ogl != null && !ogl.isEmpty()) {
                openGLRenderer = "OGL: " + simplifyGpuString(ogl);
                updated = true;
            }
            if (vk != null && !vk.isEmpty()) {
                vulkanDeviceName = "VK: " + simplifyGpuString(vk);
                updated = true;
            }
            return updated;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read GPU info file", e);
            return false;
        }
    }

    private void fallbackGpuInfo() {
        String ogl = getProp("ro.hardware.egl");
        String vk = getProp("ro.hardware.vulkan");
        if (ogl == null || ogl.isEmpty()) ogl = getProp("ro.board.platform");
        if (vk == null || vk.isEmpty()) vk = ogl;

        openGLRenderer = "OGL: " + simplifyGpuString(ogl != null ? ogl : "Unknown");
        vulkanDeviceName = "VK: " + simplifyGpuString(vk != null ? vk : "Unknown");
    }

    private String simplifyGpuString(String raw) {
        if (raw == null || raw.isEmpty()) return "?";
        String lower = raw.toLowerCase();
        String[] keywords = {
            "gl4es", "zink", "virgl", "softpipe", "swrast", "llvm",
            "turnip", "venus", "wrapper", "panfrost", "v3d",
            "adreno", "mali", "powervr", "nvidia", "geforce", "radeon", "amd", "intel", "iris", "virtio"
        };
        for (String kw : keywords) {
            if (lower.contains(kw)) {
                int idx = lower.indexOf(kw);
                return raw.substring(idx, idx + kw.length());
            }
        }
        String[] parts = raw.trim().split("\\s+");
        return parts[0];
    }

    private String getProp(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            return br.readLine();
        } catch (Exception e) {
            return null;
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
        if (gpuScheduler != null) gpuScheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}