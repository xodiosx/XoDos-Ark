package com.termux.x11;

import app.xodos2.R;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.x11.controller.container.Container;
import com.termux.x11.controller.container.Shortcut;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.TouchpadView;
import com.termux.x11.controller.winhandler.ProcessInfo;
import com.termux.x11.controller.winhandler.TaskManagerDialog;
import com.termux.x11.controller.winhandler.WinHandler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;

/**
 * Optional layer that adds Winlator-style touch controls, gamepad routing,
 * Wine bridge, task manager, HUD, and the side drawer to the stock
 * Termux:X11 MainActivity. Requires zero edits to LoriePreferences.java.
 */
public class Controls {
    private static final String TAG = "X11Controls";
    private static final WeakHashMap<Activity, Controls> INSTANCES = new WeakHashMap<>();

    public static Controls of(Activity a) { return INSTANCES.get(a); }

    private final MainActivity activity;

    // Input controls
    private WinHandler winHandler;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private InputControlsManager inputControlsManager;
    private Container container;
    private Shortcut shortcut;
    private float globalCursorSpeed = 1.0f;
private Runnable editInputControlsCallback;

    // Drawer
    private DrawerLayout drawerLayout;
    private FrameLayout drawerPanel;

    // HUD
    private HudService hudService;
    private boolean isBound = false;
    private boolean isResumed = false;

    public Controls(MainActivity a) {
        this.activity = a;
        INSTANCES.put(a, this);
    }

    /* ============================================================
     * LIFECYCLE
     * ============================================================ */
    public void attach(Bundle savedInstanceState) {
        setupWinHandler();
        setupInputControls();
        setupDrawer();
        watchHudPreference();
        startHudIfEnabled();
        checkConnectedControllers();
    }

    public void detach() {
        try { if (winHandler != null) winHandler.stop(); } catch (Exception ignored) {}
        unbindHud();
        INSTANCES.remove(activity);
    }

    public void onResume() {
        isResumed = true;
        startHudIfEnabled();
        if (isBound && hudService != null) hudService.attachToActivity(activity);
    }
    public void onPause()  { isResumed = false; }
    public void onStart()  { startHudIfEnabled(); }
    public void onStop()   {
        unbindHud();
        // Don't stop the service — HUD keeps running in background
    }

    /* ============================================================
     * WIN HANDLER
     * ============================================================ */
    private void setupWinHandler() {
        LorieView lv = activity.getLorieView();
        if (lv == null) return;
        winHandler = new WinHandler(activity);
        try { lv.setWinHandler(winHandler); }
        catch (Throwable t) { Log.w(TAG, "LorieView.setWinHandler missing — add it from your fork"); }
        Executors.newSingleThreadExecutor().execute(() -> {
            try { winHandler.start(); } catch (Exception e) { Log.e(TAG, "winHandler.start", e); }
        });
    }

    public boolean isWineRunning() {
        try { return Runtime.getRuntime().exec("pgrep -f winhandler.exe").waitFor() == 0; }
        catch (Exception e) { return false; }
    }

    /* ============================================================
     * INPUT CONTROLS
     * ============================================================ */
        private void setupInputControls() {
        LorieView lv = activity.getLorieView();
        FrameLayout frm = activity.findViewById(R.id.frame);
        if (lv == null || frm == null) return;



        touchpadView = new TouchpadView(activity, lv);
        touchpadView.setSensitivity(1.0f);
        touchpadView.setVisibility(View.GONE);
        frm.addView(touchpadView);

        inputControlsView = new InputControlsView(activity);
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(activity);
        inputControlsView.setOverlayOpacity(p.getFloat("overlay_opacity", 0.5f));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(lv);
        inputControlsView.setVisibility(View.GONE);
        frm.addView(inputControlsView);

        inputControlsManager = new InputControlsManager(activity);

        String shortcutPath = activity.getIntent().getStringExtra("shortcut_path");
        container = new Container(0);
        if (shortcutPath != null && !shortcutPath.isEmpty())
            shortcut = new Shortcut(container, new File(shortcutPath));
    }

    public InputControlsView getInputControlsView() { return inputControlsView; }
    public WinHandler getWinHandler() { return winHandler; }
    public InputControlsManager getInputControlsManager() { return inputControlsManager; }

    /* ============================================================
 * INPUT CONTROLS — dialog + activate
 * ============================================================ */
public void showInputControls() {
    // Old no-arg method kept for compatibility. Real dialog lives in
    // showInputControlsDialog().
    showInputControlsDialog();
}

public void showInputControls(ControlsProfile profile) {
    if (inputControlsView == null) return;
    inputControlsView.setVisibility(View.VISIBLE);
    inputControlsView.requestFocus();
    inputControlsView.setProfile(profile);

    if (touchpadView != null) {
        if (profile != null)
            touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setVisibility(View.VISIBLE);
    }
    inputControlsView.invalidate();

    if (drawerLayout != null)
        drawerLayout.closeDrawer(GravityCompat.START);
}

public void showInputControlsDialog() {
    if (inputControlsView == null || inputControlsManager == null) {
        Toast.makeText(activity, "Input controls not ready", Toast.LENGTH_SHORT).show();
        return;
    }
    if (activity.isFinishing() || activity.isDestroyed()) return;

    // Refresh profiles so changes from InputControllerActivity are picked up.
    try { inputControlsManager.loadProfiles(true); } catch (Throwable ignored) {}

    final com.termux.x11.controller.contentdialog.ContentDialog dialog =
            new com.termux.x11.controller.contentdialog.ContentDialog(
                    activity, R.layout.input_controls_dialog);
    dialog.setTitle(R.string.input_controls);
    dialog.setIcon(R.drawable.icon_input_controls);

    final android.widget.Spinner sProfile = dialog.findViewById(R.id.SProfile);

    Runnable loadProfileSpinner = () -> {
        ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles();
        ArrayList<String> items = new ArrayList<>();
        int selected = 0;
        items.add("-- " + activity.getString(R.string.disabled) + " --");
        for (int i = 0; i < profiles.size(); i++) {
            ControlsProfile p = profiles.get(i);
            if (p == inputControlsView.getProfile()) selected = i + 1;
            items.add(p.getName());
        }
        sProfile.setAdapter(new android.widget.ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_dropdown_item, items));
        sProfile.setSelection(selected);
    };
    loadProfileSpinner.run();

    final android.widget.CheckBox cbLockCursor = dialog.findViewById(R.id.CBLockCursor);
    final android.widget.CheckBox cbEnableTouchScreen = dialog.findViewById(R.id.CBTouchScreen);
    final android.widget.CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);

    LorieView lv = activity.getLorieView();
    if (lv != null && lv.cursorLocker != null)
        cbLockCursor.setChecked(lv.cursorLocker.isEnabled());
    cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

    dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
        int position = sProfile.getSelectedItemPosition();
        Intent intent = new Intent(activity,
                com.termux.x11.controller.InputControllerActivity.class);
        intent.putExtra("edit_input_controls", true);
        intent.putExtra("selected_profile_id",
                position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
        editInputControlsCallback = () -> {
            hideInputControls();
            inputControlsManager.loadProfiles(true);
            loadProfileSpinner.run();
        };
        // Note: onActivityResult is NOT wired up — profiles are reloaded each
        // time the dialog opens, so this callback is a no-op optimization.
        activity.startActivity(intent);
    });

    dialog.setOnConfirmCallback(() -> {
        LorieView l = activity.getLorieView();
        if (l != null && l.cursorLocker != null)
            l.cursorLocker.setEnabled(cbLockCursor.isChecked());

        inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());

        int position = sProfile.getSelectedItemPosition();
        if (position > 0) {
            if (touchpadView != null) {
                touchpadView.setTouchMode(cbEnableTouchScreen.isChecked()
                        ? TouchpadView.TouchMode.TOUCH_SCREEN
                        : TouchpadView.TouchMode.TRACK_PAD);
            }
            showInputControls(inputControlsManager.getProfiles().get(position - 1));
        } else {
            hideInputControls();
        }
    });

    dialog.show();
}

    public void hideInputControls() {
        if (inputControlsView == null) return;
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);
        inputControlsView.invalidate();
        if (touchpadView != null) touchpadView.setVisibility(View.GONE);
    }

    /* ============================================================
     * GAMEPAD ROUTING
     * ============================================================ */
    public boolean isIgnoredDevice(InputDevice d) {
        if (d == null) return true;
        String n = d.getName().toLowerCase();
        return n.contains("uinput-fpc") || n.contains("fingerprint")
                || n.contains("fpc1020") || n.contains("goodix") || d.isVirtual();
    }

    public boolean isGamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || isIgnoredDevice(d)) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                return true;
        }
        return false;
    }

    private void checkConnectedControllers() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && (d.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    && !isIgnoredDevice(d)) {
                String msg = "Controller connected: " + d.getName() + " (id " + id + ")";
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                Log.d(TAG, msg);
            }
        }
    }

    /**
     * Called AFTER super.dispatchKeyEvent(), so the key has already been handed
     * to LorieView's onKeyListener (i.e. the X11 client). We only ADD WinHandler
     * and overlay-input-controls routing on top.
     */
    public boolean dispatchGamepadKeyEvent(KeyEvent e) {
        if (e.getDevice() == null || isIgnoredDevice(e.getDevice()) || !isGamepadConnected())
            return false;
        boolean handled = false;
        try {
            if (isWineRunning() && winHandler != null) { winHandler.onKeyEvent(e); handled = true; }
        } catch (Throwable ignored) {}
        if (inputControlsView != null && inputControlsView.dispatchKeyEvent(e)) handled = true;
        return handled;
    }

    public boolean dispatchGamepadMotionEvent(MotionEvent e) {
        if (e.getDevice() == null || isIgnoredDevice(e.getDevice()) || !isGamepadConnected())
            return false;
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK)
            return false;
        boolean handled = false;
        try {
            if (isWineRunning() && winHandler != null) { winHandler.onGenericMotionEvent(e); handled = true; }
        } catch (Throwable ignored) {}
        if (inputControlsView != null && inputControlsView.dispatchGenericMotionEvent(e)) handled = true;
        return handled;
    }

    /** Called BEFORE super.dispatchTouchEvent() so overlay can swallow touches. */
    public boolean dispatchTouch(MotionEvent ev) {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) return false;
        if (inputControlsView == null
                || inputControlsView.getVisibility() != View.VISIBLE
                || inputControlsView.getProfile() == null) return false;

        int[] loc = new int[2];
        inputControlsView.getLocationOnScreen(loc);
        float x = ev.getRawX(), y = ev.getRawY();
        if (x < loc[0] || x > loc[0] + inputControlsView.getWidth()
                || y < loc[1] || y > loc[1] + inputControlsView.getHeight()) return false;

        MotionEvent adj = MotionEvent.obtain(ev);
        adj.setLocation(x - loc[0], y - loc[1]);
        boolean handled = inputControlsView.handleTouchEvent(adj);
        adj.recycle();
        return handled;
    }

    /* ============================================================
     * PROCESS LIST + TASK MANAGER
     * ============================================================ */
    public List<ProcessInfo> getAndroidProcessList() {
    List<ProcessInfo> out = new ArrayList<>();
    File[] files = new File("/proc").listFiles();
    if (files == null) {
        Log.w(TAG, "getAndroidProcessList: /proc not listable");
        return out;
    }
    int myUid = android.os.Process.myUid();
    int scanned = 0, matchedUid = 0;
    for (File f : files) {
        if (!f.isDirectory() || !f.getName().matches("\\d+")) continue;
        scanned++;
        ProcessInfo info = readProcessInfo(Integer.parseInt(f.getName()), myUid);
        if (info != null) { matchedUid++; out.add(info); }
    }
    Log.d(TAG, "getAndroidProcessList: scanned=" + scanned
            + " matchedUid=" + matchedUid + " out=" + out.size() + " uid=" + myUid);
    return out;
}

    private ProcessInfo readProcessInfo(int pid, int myUid) {
        File status = new File("/proc/" + pid + "/status");
        if (!status.exists()) return null;
        String name = null; int uid = -1;
        try (BufferedReader r = new BufferedReader(new FileReader(status))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("Uid:")) { uid = Integer.parseInt(l.split("\\s+")[1]); if (uid != myUid) return null; }
                else if (l.startsWith("Name:")) name = l.substring(5).trim();
            }
        } catch (IOException | NumberFormatException e) { return null; }
        if (name == null) name = "unknown";
        long mem = 0;
        File statm = new File("/proc/" + pid + "/statm");
        if (statm.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(statm))) {
                String[] p = r.readLine().split("\\s+");
                if (p.length >= 2) mem = Long.parseLong(p[1]) * 4096;
            } catch (IOException | NumberFormatException ignored) {}
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return new ProcessInfo(pid, name, mem, (1 << cores) - 1, false);
    }

    public void showProcessManager() {
        try { new TaskManagerDialog(activity).show(); }
        catch (Exception e) { Log.e(TAG, "TaskManagerDialog", e); }
    }

    /* ============================================================
     * DRAWER  — swipe works initially; back toggles + locks
     * ============================================================ */
    @SuppressLint("RestrictedApi")
    private void setupDrawer() {
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null || root.getChildCount() == 0) return;
        View main = root.getChildAt(0);

        drawerLayout = new DrawerLayout(activity);
        root.removeViewAt(0);
        root.addView(drawerLayout, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        drawerLayout.addView(main, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawerPanel = new FrameLayout(activity);
        int w = (int) (320 * activity.getResources().getDisplayMetrics().density);
        DrawerLayout.LayoutParams lp = new DrawerLayout.LayoutParams(
                w, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = GravityCompat.START;
        drawerPanel.setLayoutParams(lp);
        drawerPanel.setBackgroundColor(Color.WHITE);
        drawerPanel.setId(View.generateViewId());
        drawerLayout.addView(drawerPanel);

        // Initial state: unlocked → swipe from left works, back opens it too.
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);

        View prefsBtn = activity.findViewById(R.id.preferences_button);
        if (prefsBtn != null) prefsBtn.setOnClickListener(v -> toggleDrawer());

        if (activity instanceof FragmentActivity) {
            ((FragmentActivity) activity).getSupportFragmentManager()
                    .beginTransaction()
                    .replace(drawerPanel.getId(), new DrawerPreferenceFragment())
                    .commitNow();
        }
    }

    public DrawerLayout getDrawerLayout() { return drawerLayout; }

    public void toggleDrawer() {
        if (drawerLayout == null) return;
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    /**
     * Returns true if the event was consumed (drawer was open and closed).
     * Matches the custom X11 behaviour:
     *   - closed  → unlock + open
     *   - open    → close + lock  (so swipe no longer works until back is pressed again)
     */
    public boolean onBackPressed() {
        if (drawerLayout == null) return false;
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            drawerLayout.postDelayed(() ->
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED), 100);
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            drawerLayout.openDrawer(GravityCompat.START);
        }
        return true;
    }

    /* ============================================================
     * HUD
     * ============================================================ */
    private final ServiceConnection hudConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            hudService = ((HudService.LocalBinder) b).getService();
            if (isResumed) hudService.attachToActivity(activity);
            isBound = true;
        }
        @Override public void onServiceDisconnected(ComponentName n) { isBound = false; hudService = null; }
    };

    private void watchHudPreference() {
        PreferenceManager.getDefaultSharedPreferences(activity)
                .registerOnSharedPreferenceChangeListener((p, k) -> {
                    if ("hud_enabled".equals(k)) {
                        if (p.getBoolean(k, false)) startHudService(); else stopHudService();
                    }
                });
    }

    private void startHudIfEnabled() {
        if (PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("hud_enabled", false))
            startHudService();
    }

    public void startHudService() {
        Intent i = new Intent(activity, HudService.class);
        activity.startService(i);
        if (!isBound) activity.bindService(i, hudConn, Context.BIND_AUTO_CREATE);
    }

    public void stopHudService() {
        unbindHud();
        activity.stopService(new Intent(activity, HudService.class));
    }

    private void unbindHud() {
        if (isBound) {
            try { activity.unbindService(hudConn); } catch (Exception ignored) {}
            isBound = false;
        }
    }

    /* ============================================================
     * MISC ACTIONS (formerly on TermuxActivityListener)
     * ============================================================ */
    public void stopDesktop() {
        try {
            LorieView lv = activity.getLorieView();
            if (lv != null && lv.connected()) lv.connect(-1);
        } catch (Throwable ignored) {}
        Toast.makeText(activity, "Desktop stopped", Toast.LENGTH_SHORT).show();
    }

    public void installX11ServerBridge() {
        // Try the fork's broadcast first; fall back to a dialog for stock Termux:X11.
        Intent i = new Intent("com.termux.action.INSTALL_X11");
        i.setPackage("com.termux");
        try {
            activity.startActivity(i);
            return;
        } catch (Exception ignored) {}
        new androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("X11 server bridge")
                .setMessage("Install the Termux:X11 start script from your Termux app. "
                        + "See the README for the exact command.")
                .setPositiveButton("OK", null)
                .show();
    }

    public void openPreferencesActivity() {
        activity.startActivity(new Intent(activity, LoriePreferences.class));
    }

    /* ============================================================
     * DRAWER FRAGMENT
     * ============================================================ */
    public static class DrawerPreferenceFragment extends PreferenceFragmentCompat
            implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

        private MainActivity activity;
        private Controls controls;

        @Override public void onAttach(@NonNull Context c) {
            super.onAttach(c);
            activity = (MainActivity) c;
            controls = Controls.of(activity);
        }

        @Override public void onCreatePreferences(Bundle b, String key) {
            setPreferencesFromResource(R.xml.drawer_preferences, key);

            PreferenceScreen scr = getPreferenceScreen();
            for (int i = 0; i < scr.getPreferenceCount(); i++) {
                Preference p = scr.getPreference(i);
                p.setOnPreferenceClickListener(this);
                p.setOnPreferenceChangeListener(this);
                if (p instanceof PreferenceCategory) {
                    PreferenceCategory cat = (PreferenceCategory) p;
                    for (int j = 0; j < cat.getPreferenceCount(); j++) {
                        Preference c = cat.getPreference(j);
                        c.setOnPreferenceClickListener(this);
                        c.setOnPreferenceChangeListener(this);
                    }
                }
            }

            // Sync switch with current state
            SwitchPreferenceCompat hud = findPreference("hud_enabled");
            if (hud != null) {
                hud.setChecked(PreferenceManager
                        .getDefaultSharedPreferences(activity)
                        .getBoolean("hud_enabled", false));
            }
        }

        private void closeDrawer() {
            if (controls != null && controls.getDrawerLayout() != null)
                controls.getDrawerLayout().closeDrawer(GravityCompat.START);
        }

        @Override public boolean onPreferenceClick(Preference p) {
            String k = p.getKey();
            if (k == null) return false;

            switch (k) {
                case "full_settings":
                    closeDrawer();
                    if (controls != null) controls.openPreferencesActivity();
                    return true;
                case "open_keyboard":
                    closeDrawer();
                    activity.toggleKeyboardVisibility();
                    return true;
                case "select_controller":
                    closeDrawer();
                    if (controls != null) controls.showInputControls();
                    
                    return true;
                case "open_progress_manager":
                    if (controls != null) controls.showProcessManager();
                    return true;
                case "install_x11_server_bridge":
                    if (controls != null) controls.installX11ServerBridge();
                    return true;
                case "stop_desktop":
                    if (controls != null) controls.stopDesktop();
                    return true;
                case "start_debug":
                    closeDrawer();
                    startDebug();
                    return true;
                case "help":
                    closeDrawer();
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md")));
                    } catch (Exception ignored) {}
                    return true;
                case "exit":
                    activity.finish();
                    return true;
            }
            return false;
        }

        @Override public boolean onPreferenceChange(Preference p, Object v) {
            if ("hud_enabled".equals(p.getKey()) && controls != null) {
                if ((Boolean) v) controls.startHudService(); else controls.stopHudService();
                return true;
            }
            return false;
        }

        private void startDebug() {
            try {
                Class<?> c = Class.forName("com.termux.x11.LogcatLogger");
                c.getMethod("start", Context.class).invoke(null, activity);
                Toast.makeText(activity, "Debug log started", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(activity, "LogcatLogger not available", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "startDebug", t);
            }
        }
    }
}