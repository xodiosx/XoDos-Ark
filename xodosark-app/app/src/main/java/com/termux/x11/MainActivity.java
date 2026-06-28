package com.termux.x11;

// Add these imports at the top with other imports
import android.net.Uri;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceCategory;
import androidx.drawerlayout.widget.DrawerLayout;
//import com.google.android.material.navigation.NavigationView;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import androidx.preference.ListPreference;
import androidx.annotation.NonNull;
import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;
import com.termux.x11.controller.winhandler.ProcessInfo;
//
import java.util.List;
import java.util.ArrayList;
import android.os.RemoteException;
import android.os.ParcelFileDescriptor;

import android.app.NotificationChannel;
import androidx.viewpager.widget.ViewPager;
import android.service.notification.StatusBarNotification;
// 
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import androidx.core.app.NotificationCompat;

//import me.weishu.reflection.Reflection;
//import com.termux.x11.R;
import android.view.InputDevice; // For InputDevice.SOURCE_GAMEPAD
import android.widget.Toast;
import android.graphics.PointF;
import com.termux.x11.input.InputEventSender;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import static android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC;
import static android.view.KeyEvent.KEYCODE_VOLUME_DOWN;
import android.app.Activity;
import android.provider.Settings;
import android.view.WindowInsets;
import androidx.appcompat.app.AlertDialog;
import java.util.Objects;
// 
import android.os.Handler;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ImageButton;
import androidx.viewpager.widget.ViewPager;
import androidx.core.app.NotificationCompat;
import androidx.core.math.MathUtils;

import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_META_LEFT;
import static android.view.KeyEvent.KEYCODE_META_RIGHT;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static com.termux.x11.CmdEntryPoint.ACTION_START;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.DragEvent;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.core.math.MathUtils;
import androidx.viewpager.widget.ViewPager;

import app.xodos2.R;
import static com.termux.x11.LoriePreferences.ACTION_PREFERENCES_CHANGED;
import android.content.pm.PackageManager;
import com.termux.x11.controller.container.Container;
import com.termux.x11.controller.container.Shortcut;
import com.termux.x11.controller.inputcontrols.InputControlsManager;
import com.termux.x11.controller.widget.InputControlsView;
import com.termux.x11.controller.widget.TouchpadView;
import com.termux.x11.controller.winhandler.TaskManagerDialog;
import com.termux.x11.controller.winhandler.WinHandler;
import com.termux.x11.input.InputEventSender;
import com.termux.x11.input.InputStub;
import com.termux.x11.input.TouchInputHandler;
import com.termux.x11.utils.FullscreenWorkaround;
import com.termux.x11.utils.KeyInterceptor;
import com.termux.x11.utils.SamsungDexUtils;
import com.termux.x11.utils.TermuxX11ExtraKeys;
import com.termux.x11.utils.X11ToolbarViewPager;

// ========== ADD THESE IMPORTS FOR PROCESS LISTING ==========
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import android.os.Process;  // for Process.myUid()
// ===========================================================

import java.io.File;
import java.util.Map;
import java.util.concurrent.Executors;

@SuppressLint("ApplySharedPref")
@SuppressWarnings({"deprecation", "unused"})
public class MainActivity extends LoriePreferences {
    static final String ACTION_STOP = "com.termux.x11.ACTION_STOP";
    public static final String ACTION_CUSTOM = "com.termux.x11.ACTION_CUSTOM";
    static final String REQUEST_LAUNCH_EXTERNAL_DISPLAY = "request_launch_external_display";
    
    protected boolean inputControllerViewHandled = false;
    
        
    public static Handler handler = new Handler();
    
    protected FrameLayout frm;
    protected View lorieContentView;
    protected TouchInputHandler mInputHandler;
    protected ICmdEntryInterface service = null;
    public TermuxX11ExtraKeys mExtraKeys;
    private Notification mNotification;
    private final int mNotificationId = 7897;
    NotificationManager mNotificationManager;
    static InputMethodManager inputMethodManager;
    private boolean mClientConnected = false;
    private View.OnKeyListener mLorieKeyListener;
    private boolean filterOutWinKey = false;
    private static final int KEY_BACK = 158;
    protected static boolean hasInit = false;
    
    private boolean isInPictureInPictureMode = false;
    private static boolean showIMEWhileExternalConnected = true;
    private static boolean externalKeyboardConnected = false;
    boolean useTermuxEKBarBehaviour = false;
    
public static Prefs prefs = null;
    private static boolean oldFullscreen = false, oldHideCutout = false;
    
private DrawerLayout drawerLayout;
//    private NavigationView navigationView;
    
        private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChangedListener = (__, key) -> onPreferencesChanged(key);
    private static boolean softKeyboardShown = false;

// hud
// service hug
private HudService hudService;
private boolean isBound = false;

private ServiceConnection hudConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        HudService.LocalBinder binder = (HudService.LocalBinder) service;
        hudService = binder.getService();
        // If activity is resumed, attach immediately
        if (isResumed) {
            hudService.attachToActivity(MainActivity.this);
        }
        isBound = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        isBound = false;
        hudService = null;
    }
};

private boolean isResumed = false;

// Call this when the HUD preference is enabled
public void startHudService() {
    Intent intent = new Intent(this, HudService.class);
    
        startService(intent);
    
    bindService(intent, hudConnection, Context.BIND_AUTO_CREATE);
}

// Call this when the HUD preference is disabled
public void stopHudService() {
    if (isBound) {
        unbindService(hudConnection);
        isBound = false;
    }
    Intent intent = new Intent(this, HudService.class);
    stopService(intent);
}

// Called from onStart to start HUD if preference is enabled
private void startHudIfEnabled() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    boolean hudEnabled = prefs.getBoolean("hud_enabled", false);
    if (hudEnabled) {
        startHudService();
    }
}


 //////////////////////////////////////////////////////////////////
private void checkConnectedControllers() {
    int[] deviceIds = InputDevice.getDeviceIds();
    for (int id : deviceIds) {
        InputDevice device = InputDevice.getDevice(id);
        if ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            && !isIgnoredDevice(device)) {
            
            String msg = "Controller:🎮 " + device.getName() + " (ID:" + id + ")";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            Log.d("ControllerDebug", msg);
        }
    }
}


 /// check fingerprint sensors that acts like gamepad
 private boolean isIgnoredDevice(InputDevice device) {
    if (device == null) return true;

    String name = device.getName().toLowerCase();

    // Ignore fingerprint or virtual devices that masquerade as gamepads
    return name.contains("uinput-fpc") ||
           name.contains("fingerprint") ||
           name.contains("fpc1020") ||   // common FPC models
           name.contains("goodix")   ||  // Goodix sensors
           device.isVirtual();          // Ignore system-generated virtual inputs
}
    
       
             private boolean isGamepadConnected() {
    int[] deviceIds = InputDevice.getDeviceIds();
    for (int id : deviceIds) {
        InputDevice device = InputDevice.getDevice(id);
        if (device == null) continue;
        if (isIgnoredDevice(device)) continue;

        if ((device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (device.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return true;
        }
    }
    return false;
}
    
public boolean isWineRunning() {
    try {
        // Fully qualify java.lang.Process to avoid conflict with android.os.Process
        java.lang.Process process = Runtime.getRuntime().exec("pgrep -f winhandler.exe");
        return process.waitFor() == 0;
    } catch (Exception e) {
        return false;
    }
}

   private String getNotificationChannel(NotificationManager notificationManager) {
    String channelId = "termux_x11_channel";
    String channelName = "Termux X11 Notifications";
    
    if (SDK_INT >= VERSION_CODES.O) {
        NotificationChannel channel = new NotificationChannel(channelId, channelName, 
            NotificationManager.IMPORTANCE_DEFAULT);
        channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        if (SDK_INT >= VERSION_CODES.Q)
            channel.setAllowBubbles(false);
        notificationManager.createNotificationChannel(channel);
    }
    return channelId;
} 


public ViewPager getTerminalToolbarViewPager() {
return findViewById(R.id.display_terminal_toolbar_view_pager);
  //  return findViewById(R.id.terminal_toolbar_view_pager);
}

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        @Override
        public void onReceive(Context context, Intent intent) {
        if (prefs == null) return;
            prefs.recheckStoringSecondaryDisplayPreferences();
            if (ACTION_START.equals(intent.getAction())) {
                try {
                    Log.v("LorieBroadcastReceiver", "Got new ACTION_START intent");
                    onReceiveConnection(intent);
                } catch (Exception e) {
                    Log.e("MainActivity", "Something went wrong while we extracted connection details from binder.", e);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                finishAffinity();
            } else if (ACTION_PREFERENCES_CHANGED.equals(intent.getAction())) {
                Log.d("MainActivity", "preference: " + intent.getStringExtra("key"));
                if (!"additionalKbdVisible".equals(intent.getStringExtra("key")))
                    onPreferencesChanged("");
            } else if (ACTION_CUSTOM.equals(intent.getAction())) {
                android.util.Log.d("ACTION_CUSTOM", "action " + intent.getStringExtra("what"));
                mInputHandler.extractUserActionFromPreferences(prefs, intent.getStringExtra("what")).accept(0, true);
            }
        }
    };

    ViewTreeObserver.OnPreDrawListener mOnPredrawListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (LorieView.connected())
                handler.post(() -> findViewById(android.R.id.content).getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener));
            return false;
        }
    };

    @SuppressLint("StaticFieldLeak")
    private static MainActivity instance;


       public MainActivity() {
        instance = this;
    }

    public static Prefs getPrefs() {
        if (prefs != null)
            return prefs;
        android.app.Activity h = X11ActivityBridge.getHostActivity();
        if (h != null)
            return X11ActivityBridge.getOrCreatePrefs(h);
        return null;
    }

    public static MainActivity getInstance() {
        if (instance != null)
            return instance;
        android.app.Activity h = X11ActivityBridge.getHostActivity();
        return (h instanceof MainActivity) ? (MainActivity) h : null;
    }

    
    // ADD THIS METHOD AT THE END OF THE CLASS (BUT BEFORE THE CLOSING BRACE)
    private void setupTermuxActivityListener() {
    this.termuxActivityListener = new TermuxActivityListener() {
        @Override
        public void onX11PreferenceSwitchChange(boolean isOpen) {
            // Handle preference switch change
            if (isOpen) {
                // Open preferences
                startActivity(new Intent(MainActivity.this, LoriePreferences.class));
            }
        }

        @Override
        public void releaseSlider(boolean open) {
            // For MainActivity, we don't have a slider UI
            Log.d("MainActivity", "Slider released: " + open);
        }

        @Override
        public void onChangeOrientation(int orientation) {
            // Set orientation for MainActivity
            setRequestedOrientation(orientation);
            
            // Also update the LorieView if connected
            if (getLorieView() != null) {
                getLorieView().regenerate();
            }
        }

        @Override
        public void reInstallX11StartScript(Activity activity) {
            // Use intent to communicate with Termux app
            Intent intent = new Intent();
            intent.setAction("com.termux.action.INSTALL_X11");
            intent.setPackage("com.xodos");
            try {
                activity.startActivity(intent);
            } catch (Exception e) {
                Log.e("MainActivity", "Failed to launch Termux installer", e);
                Toast.makeText(activity, "Please install Termux app first", Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void stopDesktop() {
            // Disconnect X11 connection
            if (LorieView.connected()) {
                // Check what method LorieView has for disconnecting
                // If there's no disconnect method, we'll just update the UI
            }
            
            // Update UI to show disconnected state
            clientConnectedStateChanged();
            
            // Show toast
            Toast.makeText(MainActivity.this, "Desktop stopped", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void openSoftwareKeyboard() {
            // Toggle keyboard visibility
            MainActivity.toggleKeyboardVisibility(MainActivity.this);
        }

        @Override
        public void showProcessManager() {
            // Show process manager dialog from MainActivity
            showProcessManagerDialog();
        }

        @Override
        public void changePreference(String key) {
            // Handle preference change in MainActivity
            onPreferencesChanged(key);
        }

        @Override
        public List<ProcessInfo> collectProcessorInfo(String tag) {
            // Return real Android process list instead of empty placeholder
            return getAndroidProcessList();
        }

       

        @Override
        public void onExitApp() {
            // Exit the app
          //  System.exit(0);
         finish();
       //     finishAffinity();
        }
    };
}
    
     
    
    private void removeFloatingButton() {
        // Implement if you add floating button functionality
    }
    
    private void showFloatingMenu() {
        // Implement if you add floating button functionality
    }

/// Remove the private methods and replace with proper implementations:

// Don't override - use the existing method from LoriePreferences
public void showInputControlsDialog() {
    // Use the existing method from LoriePreferences
    if (this instanceof LoriePreferences) {
        super.showInputControlsDialog();
    } else {
        // Fallback if needed
        if (inputControlsView != null) {
            // Show the input controls directly
            inputControlsView.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Input controls enabled", Toast.LENGTH_SHORT).show();
        }
    }
}

// Don't override - use the existing method from LoriePreferences  
public void installX11ServerBridge() {
    // Use the existing method from LoriePreferences
    if (this instanceof LoriePreferences) {
        super.installX11ServerBridge();
    } else {
        // Fallback
        if (termuxActivityListener != null) {
            termuxActivityListener.reInstallX11StartScript(this);
        }
    }
}

// Don't override - use the existing method from LoriePreferences
public void stopDesktop() {
    // Use the existing method from LoriePreferences
    if (this instanceof LoriePreferences) {
        super.stopDesktop();
    } else {
        // Disconnect from X11
        if (LorieView.connected()) {
            // There's no disconnect method, so connect with -1 to disconnect
            LorieView.connect(-1);
        }
    }
}


private void startDebugMode() {
    // Start debug mode
    Toast.makeText(this, "Debug mode started", Toast.LENGTH_SHORT).show();
 //   
 LogcatLogger.stop();
 LogcatLogger.start(this);
}



private void showPreferencesInDrawer() {
    try {
        FrameLayout prefContainer = findViewById(R.id.preferences_container);
        prefContainer.setVisibility(View.VISIBLE);
        
        // Load drawer preferences fragment
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.preferences_container, new DrawerPreferenceFragment())
            .commit();
            
    } catch (Exception e) {
        Log.e("MainActivity", "Error showing preferences", e);
        Toast.makeText(this, "Failed to load preferences", Toast.LENGTH_SHORT).show();
    }
}
// Update onBackPressed to handle drawer navigation
private long backPressedTime = 0;

@Override
public void onBackPressed() {
    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
        // Close drawer and lock it again
        drawerLayout.closeDrawer(GravityCompat.START);
        
        // Re-lock the drawer after closing
        drawerLayout.postDelayed(() -> {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }, 100);
        
        // Remove fragment
        FrameLayout prefContainer = findViewById(R.id.preferences_container);
        if (prefContainer != null) {
            prefContainer.removeAllViews();
            prefContainer.setVisibility(View.GONE);
        }
        
        // Give focus back to LorieView
        LorieView lorie = getLorieView();
        if (lorie != null) {
            lorie.requestFocus();
        }
    } else {
    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            showPreferencesInDrawer();
            drawerLayout.openDrawer(GravityCompat.START);
        // Double tap to exit
      /*  if (backPressedTime + 2000 > System.currentTimeMillis()) {
            //super.onBackPressed();


            finish();
        } else {
            Toast.makeText(this, "Press back 2 times to exit", Toast.LENGTH_SHORT).show();
        }
        
        backPressedTime = System.currentTimeMillis();
        */
    }
}

public void prepareToExit() {
    Log.d("MainActivity", "prepareToExit called from notification");
    
    // Run on UI thread to ensure proper execution
    runOnUiThread(() -> {
        try {
            // 1. Stop any services first
            stopDesktop();
            
            // 2. Cancel notification
            if (mNotificationManager != null) {
                mNotificationManager.cancel(mNotificationId);
            }
            
            // 3. Disconnect X11 connection
            if (LorieView.connected()) {
                LorieView.connect(-1); // This should disconnect
            }
            
            // 4. Close activity if it's still valid
            if (!isFinishing() && !isDestroyed()) {
                finish();
            }
            
            // 5. Exit process completely
            handler.postDelayed(() -> {
               
            // System.exit(0);
            finish();
            }, 100);
            
        } catch (Exception e) {
            Log.e("MainActivity", "Error in prepareToExit", e);
            // Fallback: just kill the process
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    });
}
//// touch fix
@Override
public boolean dispatchTouchEvent(MotionEvent ev) {
    Log.d("MainActivity", "dispatchTouchEvent - Action: " + 
          MotionEvent.actionToString(ev.getAction()));
    
    // Don't handle touches when drawer is open
    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
        return super.dispatchTouchEvent(ev);
    }
    
    // If input controls are visible and have a profile, let them try to handle it first
    if (inputControlsView != null && 
        inputControlsView.getVisibility() == View.VISIBLE &&
        inputControlsView.getProfile() != null) {
        
        // Check if touch is within input controls bounds
        int[] location = new int[2];
        inputControlsView.getLocationOnScreen(location);
        
        float x = ev.getRawX();
        float y = ev.getRawY();
        
        if (x >= location[0] && x <= location[0] + inputControlsView.getWidth() &&
            y >= location[1] && y <= location[1] + inputControlsView.getHeight()) {
            
            // Convert to view coordinates
            float viewX = x - location[0];
            float viewY = y - location[1];
            
            MotionEvent adjustedEvent = MotionEvent.obtain(ev);
            adjustedEvent.setLocation(viewX, viewY);
            
            boolean handled = inputControlsView.handleTouchEvent(adjustedEvent);
            adjustedEvent.recycle();
            
            if (handled) {
                Log.d("MainActivity", "Input controls handled touch in dispatchTouchEvent");
                return true;
            }
        }
    }
    
    // If not handled by input controls, pass to normal touch handling
    return super.dispatchTouchEvent(ev);
}



        @Override
    @SuppressLint({"AppCompatMethod", "ObsoleteSdkInt", "ClickableViewAccessibility", "WrongConstant", "UnspecifiedRegisterReceiverFlag"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        prefs = new Prefs(this.getApplicationContext());
        setupTermuxActivityListener();
//setContentView(R.layout.main_activity);
    setContentView(R.layout.main_activity_content); 
  drawerLayout = findViewById(R.id.drawer_layout);
   //     navigationView = findViewById(R.id.nav_view);
lorieContentView = findViewById(R.id.id_display_window);
        frm = findViewById(R.id.frame);
  
// Set up the preferences button to open the drawer with settings
    


        int modeValue = Integer.parseInt(prefs.touchMode.get()) - 1;
        if (modeValue > 2) {
            prefs.touchMode.put("1");
        }

        oldFullscreen = prefs.fullscreen.get();
        oldHideCutout = prefs.hideCutout.get();

        //  if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
        // 
      //  drawerLayout.closeDrawer(GravityCompat.START);
        showPreferencesInDrawer();

//prefs.get().registerOnSharedPreferenceChangeListener(preferencesChangedListener)

// Set up the preferences button to open the drawer
        findViewById(R.id.preferences_button).setOnClickListener(v -> {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            // Show the drawer with settings
            showPreferencesInDrawer();
            drawerLayout.openDrawer(GravityCompat.START);
        }
    });


findViewById(R.id.exit_button).setOnClickListener((l) -> finish());
        LorieView lorieView = findViewById(R.id.lorieView);
                X11ActivityBridge.setLorieView(lorieView);
        X11ActivityBridge.setHostActivity(this);
        View lorieParent = (View) lorieView.getParent();

        mInputHandler = new TouchInputHandler(this, new InputEventSender(lorieView));
        int touch_sensitivity = preferences.getInt("touch_sensitivity", 1);
        mInputHandler.setLongPressedDelay(touch_sensitivity);
       mLorieKeyListener = (v, k, e) -> {

///////// fixing controller binding and support

if (e.getDevice() == null) {
       return mInputHandler.sendKeyEvent(e);
   }

            if (k == KEYCODE_BACK) {
                if (softKeyboardShown) {
                    if (e.getAction() == ACTION_UP) {
                        closeSoftKeyboard();
                    }
                    
                    return false;
                }
                
            }
            
            InputDevice dev = e.getDevice();
            boolean result = mInputHandler.sendKeyEvent(e);

  //  Toast.makeText(this,"Handled Key: " + KeyEvent.keyCodeToString(e.getKeyCode()),Toast.LENGTH_SHORT).show();
    
 //   Toast.makeText(this, "Handled:=" + e, Toast.LENGTH_SHORT).show();
 
                
                if (!isIgnoredDevice(dev) && isGamepadConnected()) {
    InputDevice device = e.getDevice();
    
//Toast.makeText(this,"Handled Key: " + KeyEvent.keyCodeToString(e.getKeyCode()),Toast.LENGTH_SHORT).show();
    
 //   Toast.makeText(this, "Handled:=" + e, Toast.LENGTH_SHORT).show();
 //   inputControlsView.dispatchKeyEvent(e);
 
 //   if (device != null && (device.getSources() & InputDevice.SOURCE_GAMEPAD) != 0) {

        boolean handledByWine = false;
        boolean handledByX11 = false;
        boolean handledByInputHandler = false;

        if (isWineRunning()) {
            winHandler.onKeyEvent(e); // usually no return value
            handledByWine = true;
        }

        // call X11
        handledByX11 = inputControlsView.dispatchKeyEvent(e);

        // call Termux fallback input        
handledByInputHandler = mInputHandler.sendKeyEvent(e);

        // Debug toast if needed
         //Toast.makeText(this, "Handled: wine=" + handledByWine + " x11=" + handledByX11 + " fallback=" + handledByInputHandler, Toast.LENGTH_SHORT).show();

        // Combine logic safely
        return handledByWine || handledByX11 || handledByInputHandler;
  //  }
    
    
}      
  // Do not steal dedicated buttons from a full external keyboard.
            if (useTermuxEKBarBehaviour && mExtraKeys != null && (dev == null || dev.isVirtual()))
                mExtraKeys.unsetSpecialKeys();
                
            return result;
        };
        
       //////////////     
   // ================= Input Listeners =================
//lorieParent.setOnTouchListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
//lorieParent.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
//lorieParent.setOnGenericMotionListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
lorieView.setOnCapturedPointerListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
//lorieView.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieView, lorieView, e));
// ===================================================
        
  ///////////     
  
   // These will be handled by setupTouchHandlingFix()
// Keep only the captured pointer listener for special cases
lorieView.setOnCapturedPointerListener((v, e) -> {
    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
        return false;
    }
    return mInputHandler.handleTouchEvent(lorieView, lorieView, e);
});



    // Clear existing listeners to avoid conflicts
    lorieView.setOnTouchListener(null);
    lorieParent.setOnTouchListener(null);
    if (frm != null) {
        frm.setOnTouchListener(null);
    }
    
    // Set up proper touch handling on the FrameLayout
    frm.setOnTouchListener(new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Log.d("TouchFix", "FrameLayout touch - Action: " + 
                  MotionEvent.actionToString(event.getAction()) +
                  " at (" + event.getX() + ", " + event.getY() + ")");
            
            // Don't handle touches when drawer is open
            if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                return false;
            }
            
            // If input controls are visible and should handle this touch
            if (inputControlsView != null && 
                inputControlsView.getVisibility() == View.VISIBLE &&
                inputControlsView.getProfile() != null) {
                
                // Get locations of views for coordinate conversion
                int[] viewLocation = new int[2];
                inputControlsView.getLocationOnScreen(viewLocation);
                int[] frameLocation = new int[2];
                frm.getLocationOnScreen(frameLocation);
                
                // Calculate adjusted coordinates
                float x = event.getX() - (viewLocation[0] - frameLocation[0]);
                float y = event.getY() - (viewLocation[1] - frameLocation[1]);
                
                // Create adjusted event
                MotionEvent adjustedEvent = MotionEvent.obtain(event);
                adjustedEvent.setLocation(x, y);
                
                // Let input controls try to handle it
                boolean handled = inputControlsView.handleTouchEvent(adjustedEvent);
                adjustedEvent.recycle();
                
                if (handled) {
                    Log.d("TouchFix", "Input controls handled touch");
                    return true;
                }
            }
            
            // If not handled by input controls, pass to LorieView
            if (mInputHandler != null && lorieView != null) {
                return mInputHandler.handleTouchEvent(lorieView, lorieView, event);
            }
            
            return false;
        }
    });
    
    // Set up direct touch handling for LorieView
    lorieView.setOnTouchListener(new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Log.d("TouchFix", "LorieView direct touch - Action: " + 
                  MotionEvent.actionToString(event.getAction()));
            
            // Don't handle touches when drawer is open
            if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                return false;
            }
            
            // If input controls are visible, let frame layout handle it
            if (inputControlsView != null && 
                inputControlsView.getVisibility() == View.VISIBLE &&
                inputControlsView.getProfile() != null) {
                return false;
            }
            
            // Handle normal LorieView touch
            if (mInputHandler != null) {
                return mInputHandler.handleTouchEvent(v, v, event);
            }
            return false;
        }
    });
    
    // Set up hover listener for mouse support
    lorieView.setOnHoverListener(new View.OnHoverListener() {
        @Override
        public boolean onHover(View v, MotionEvent event) {
            if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                return false;
            }
            
            if (mInputHandler != null) {
                return mInputHandler.handleTouchEvent(v, v, event);
            }
            return false;
        }
    });

     
  /*       // Set up basic touch listeners - let dispatchTouchEvent handle the complex routing
lorieParent.setOnTouchListener((v, event) -> {
    // Don't handle touches when drawer is open
    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
        return false;
    }
    
    // If input controls are visible, let dispatchTouchEvent handle it
    if (inputControlsView != null && 
        inputControlsView.getVisibility() == View.VISIBLE &&
        inputControlsView.getProfile() != null) {
        return false;
    }
    
    // Otherwise handle normal touch
    return mInputHandler.handleTouchEvent(lorieView, lorieView, event);
});

lorieView.setOnHoverListener((v, e) -> {
    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
        return false;
    }
    return mInputHandler.handleTouchEvent(lorieView, lorieView, e);
});
        
 */       
        
//        lorieParent.setOnTouchListener((v, event) -> true);
//        lorieView.setOnHoverListener((v, e) -> mInputHandler.handleTouchEvent(lorieParent, lorieView, e));
        
    lorieView.setOnGenericMotionListener((v, e) -> {
    if (!isIgnoredDevice(e.getDevice()) && isGamepadConnected() && (e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
        // Send to Wine if running
        if (isWineRunning()) {
            winHandler.onGenericMotionEvent(e);
        }
        
        // Always send to X11
        boolean handledByX11 = inputControlsView.dispatchGenericMotionEvent(e);
        
        return true;
    }
    return false;
});
        
        //=====================
        
            
        
        
        lorieView.setOnKeyListener(mLorieKeyListener);

        lorieView.setCallback((surfaceWidth, surfaceHeight, screenWidth, screenHeight) -> {
            String name;
            int framerate = (int) ((lorieView.getDisplay() != null) ? lorieView.getDisplay().getRefreshRate() : 30);

            mInputHandler.handleHostSizeChanged(surfaceWidth, surfaceHeight);
            mInputHandler.handleClientSizeChanged(screenWidth, screenHeight);
            lorieView.screenInfo.handleHostSizeChanged(surfaceWidth, surfaceHeight);
            lorieView.screenInfo.handleClientSizeChanged(screenWidth, screenHeight);
            if (lorieView.getDisplay() == null || lorieView.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY)
                name = "0";
            else if (SamsungDexUtils.checkDeXEnabled(this))
                name = "Dex Display";
            else
                name = "External Display";
            LorieView.sendWindowChange(screenWidth, screenHeight, framerate, name);
        });

        registerReceiver(receiver, new IntentFilter(ACTION_START) {{
            addAction(ACTION_PREFERENCES_CHANGED);
            addAction(ACTION_STOP);
            addAction(ACTION_CUSTOM);
        }}, SDK_INT >= VERSION_CODES.TIRAMISU ? RECEIVER_EXPORTED : 0);

        inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        // Taken from Stackoverflow answer https://stackoverflow.com/questions/7417123/android-how-to-adjust-layout-in-full-screen-mode-when-softkeyboard-is-visible/7509285#
      //  FullscreenWorkaround.assistActivity(this);

mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        //mNotification = buildNotification();
     //   mNotificationManager.notify(mNotificationId, mNotification);

        if (tryConnect()) {
            final View content = findViewById(android.R.id.content);
            content.getViewTreeObserver().addOnPreDrawListener(mOnPredrawListener);
            handler.postDelayed(() -> content.getViewTreeObserver().removeOnPreDrawListener(mOnPredrawListener), 500);
        }
        onPreferencesChanged("");
        toggleExtraKeys(false, false);
        initStylusAuxButtons();
        initMouseAuxButtons();
        setupInputController();
        checkConnectedControllers(); 
        
        if (SDK_INT >= VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PERMISSION_GRANTED
            && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        winHandler = new WinHandler(this);
        lorieView.setWinHandler(winHandler);
        Executors.newSingleThreadExecutor().execute(() -> {
            winHandler.start();
        });
        
     //       onReceiveConnection(getIntent());
   //     findViewById(android.R.id.content).addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> makeSureHelpersAreVisibleAndInScreenBounds());
   X11ActivityBridge.onLorieViewReadyForConnect();
     }


    private static void closeSoftKeyboard() {
        inputMethodManager.hideSoftInputFromWindow(getInstance().getWindow().getDecorView().getRootView().getWindowToken(), 0);
        softKeyboardShown = false;
    }

    private static void openSoftKeyboard() {
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        softKeyboardShown = true;
    }

    @Override
    protected void onDestroy() {
    stopHudService();  
    LogcatLogger.stop();   
        winHandler.stop();
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void setupInputController() {
        xServer = getLorieView();
        globalCursorSpeed = 1.0f;
        touchpadView = new TouchpadView(this, xServer);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setVisibility(View.GONE);
//        touchpadView.setBackground(getDrawable(R.drawable.touchpad_background));
        frm.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        frm.addView(inputControlsView);
        inputControlsManager = new InputControlsManager(this);
        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        container = new Container(0);
        if (shortcutPath != null && !shortcutPath.isEmpty())
            shortcut = new Shortcut(container, new File(shortcutPath));

    }

    //Register the needed events to handle stylus as left, middle and right click
    @SuppressLint("ClickableViewAccessibility")
    private void initStylusAuxButtons() {
  //  final ViewPager pager = getTerminalToolbarViewPager();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        boolean stylusMenuEnabled = p.getBoolean("showStylusClickOverride", false);
      //boolean stylusMenuEnabled = prefs.showStylusClickOverride.get() && LorieView.connected();
          final float menuUnselectedTrasparency = 0.66f;
        final float menuSelectedTrasparency = 1.0f;
        Button left = findViewById(R.id.button_left_click);
        Button right = findViewById(R.id.button_right_click);
        Button middle = findViewById(R.id.button_middle_click);
        Button visibility = findViewById(R.id.button_visibility);
        LinearLayout overlay = findViewById(R.id.mouse_helper_visibility);
        LinearLayout buttons = findViewById(R.id.mouse_helper_secondary_layer);
        overlay.setOnTouchListener((v, e) -> true);
        overlay.setOnHoverListener((v, e) -> true);
        overlay.setOnGenericMotionListener((v, e) -> true);
        overlay.setOnCapturedPointerListener((v, e) -> true);
        overlay.setVisibility(stylusMenuEnabled ? VISIBLE : View.GONE);
        View.OnClickListener listener = view -> {
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = (view.equals(left) ? 1 : (view.equals(middle) ? 2 : (view.equals(right) ? 4 : 0)));
            left.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 1) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            middle.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 2) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            right.setAlpha((TouchInputHandler.STYLUS_INPUT_HELPER_MODE == 4) ? menuSelectedTrasparency : menuUnselectedTrasparency);
            visibility.setAlpha(menuUnselectedTrasparency);
        };

        left.setOnClickListener(listener);
        middle.setOnClickListener(listener);
        right.setOnClickListener(listener);

        visibility.setOnClickListener(view -> {
            if (buttons.getVisibility() == VISIBLE) {
                buttons.setVisibility(View.GONE);
                visibility.setAlpha(menuUnselectedTrasparency);
                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                visibility.setText(m == 1 ? "L" : (m == 2 ? "M" : (m == 3 ? "R" : "U")));
            } else {
                buttons.setVisibility(VISIBLE);
                visibility.setAlpha(menuUnselectedTrasparency);
                visibility.setText("X");

                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - 4 * left.getWidth();
                float maxY = frm.getHeight() - 4 * left.getHeight();

                //Make sure the Stylus menu is fully inside the screen
                overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));

                int m = TouchInputHandler.STYLUS_INPUT_HELPER_MODE;
                listener.onClick(m == 1 ? left : (m == 2 ? middle : (m == 3 ? right : left)));
            }
        });
        //Simulated mouse click 1 = left , 2 = middle , 3 = right
        TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
        listener.onClick(left);

        visibility.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("", ""), new View.DragShadowBuilder(visibility) {
                public void onDrawShadow(Canvas canvas) {}
            }, null, View.DRAG_FLAG_GLOBAL);

            frm.setOnDragListener((v2, event) -> {
                //Calculate screen border making sure btn is fully inside the view
                float maxX = frm.getWidth() - visibility.getWidth();
                float maxY = frm.getHeight() - visibility.getHeight();

                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_LOCATION:
                        //Center touch location with btn icon
                        float dX = event.getX() - visibility.getWidth() / 2.0f;
                        float dY = event.getY() - visibility.getHeight() / 2.0f;

                        //Make sure the dragged btn is inside the view with clamp
                        overlay.setX(MathUtils.clamp(dX, 0, maxX));
                        overlay.setY(MathUtils.clamp(dY, 0, maxY));
                        break;
                    case DragEvent.ACTION_DRAG_ENDED:
                        //Make sure the dragged btn is inside the view
                        overlay.setX(MathUtils.clamp(overlay.getX(), 0, maxX));
                        overlay.setY(MathUtils.clamp(overlay.getY(), 0, maxY));
                        break;
                }
                return true;
            });

            return true;
        });
    }

 


    void setSize(View v, int width, int height) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        p.width = (int) (width * getResources().getDisplayMetrics().density);
        p.height = (int) (height * getResources().getDisplayMetrics().density);
        v.setLayoutParams(p);
        v.setMinimumWidth((int) (width * getResources().getDisplayMetrics().density));
        v.setMinimumHeight((int) (height * getResources().getDisplayMetrics().density));
    }

    @SuppressLint("ClickableViewAccessibility")
    void initMouseAuxButtons() {
        Button left = findViewById(R.id.mouse_button_left_click);
        Button right = findViewById(R.id.mouse_button_right_click);
        Button middle = findViewById(R.id.mouse_button_middle_click);
        ImageButton pos = findViewById(R.id.mouse_buttons_position);
        LinearLayout primaryLayer = findViewById(R.id.mouse_buttons);
        LinearLayout secondaryLayer = findViewById(R.id.mouse_buttons_secondary_layer);

        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        boolean mouseHelperEnabled = p.getBoolean("showMouseHelper", false) && "1".equals(p.getString("touchMode", "1"));
        primaryLayer.setVisibility(mouseHelperEnabled ? VISIBLE : View.GONE);

        pos.setOnClickListener((v) -> {
            if (secondaryLayer.getOrientation() == LinearLayout.HORIZONTAL) {
                setSize(left, 48, 96);
                setSize(right, 48, 96);
                secondaryLayer.setOrientation(LinearLayout.VERTICAL);
            } else {
                setSize(left, 96, 48);
                setSize(right, 96, 48);
                secondaryLayer.setOrientation(LinearLayout.HORIZONTAL);
            }
            handler.postDelayed(() -> {
                int[] offset = new int[2];
                frm.getLocationOnScreen(offset);
                primaryLayer.setX(MathUtils.clamp(primaryLayer.getX(), offset[0], offset[0] + frm.getWidth() - primaryLayer.getWidth()));
                primaryLayer.setY(MathUtils.clamp(primaryLayer.getY(), offset[1], offset[1] + frm.getHeight() - primaryLayer.getHeight()));
            }, 10);
        });

        Map.of(left, InputStub.BUTTON_LEFT, middle, InputStub.BUTTON_MIDDLE, right, InputStub.BUTTON_RIGHT)
            .forEach((v, b) -> v.setOnTouchListener((__, e) -> {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        getLorieView().sendMouseEvent(0, 0, b, true, true);
                        v.setPressed(true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                        getLorieView().sendMouseEvent(0, 0, b, false, true);
                        v.setPressed(false);
                        break;
                }
                return true;
            }));

        pos.setOnTouchListener(new View.OnTouchListener() {
            final int touchSlop = (int) Math.pow(ViewConfiguration.get(MainActivity.this).getScaledTouchSlop(), 2);
            final int tapTimeout = ViewConfiguration.getTapTimeout();
            final float[] startOffset = new float[2];
            final int[] startPosition = new int[2];
            long startTime;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        primaryLayer.getLocationOnScreen(startPosition);
                        startOffset[0] = e.getX();
                        startOffset[1] = e.getY();
                        startTime = SystemClock.uptimeMillis();
                        pos.setPressed(true);
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        int[] offset = new int[2];
                        int[] offset2 = new int[2];
                        primaryLayer.getLocationOnScreen(offset);
                        frm.getLocationOnScreen(offset2);
                        primaryLayer.setX(MathUtils.clamp(offset[0] - startOffset[0] + e.getX(), offset2[0], offset2[0] + frm.getWidth() - primaryLayer.getWidth()));
                        primaryLayer.setY(MathUtils.clamp(offset[1] - startOffset[1] + e.getY(), offset2[1], offset2[1] + frm.getHeight() - primaryLayer.getHeight()));
                        break;
                    }
                    case MotionEvent.ACTION_UP: {
                        final int[] _pos = new int[2];
                        primaryLayer.getLocationOnScreen(_pos);
                        int deltaX = (int) (startOffset[0] - e.getX()) + (startPosition[0] - _pos[0]);
                        int deltaY = (int) (startOffset[1] - e.getY()) + (startPosition[1] - _pos[1]);
                        pos.setPressed(false);

                        if (deltaX * deltaX + deltaY * deltaY < touchSlop && SystemClock.uptimeMillis() - startTime <= tapTimeout) {
                            v.performClick();
                            return true;
                        }
                        break;
                    }
                }
                return true;
            }
        });
    }

    void onReceiveConnection(Intent intent) {
        Bundle bundle = intent == null ? null : intent.getBundleExtra(null);
        IBinder ibinder = bundle == null ? null : bundle.getBinder(null);
        if (ibinder == null)
            return;

        service = ICmdEntryInterface.Stub.asInterface(ibinder);
        try {
            service.asBinder().linkToDeath(() -> {
                service = null;

                Log.v("Lorie", "Disconnected");
                runOnUiThread(() -> {
                    LorieView.connect(-1);
                    clientConnectedStateChanged();
                });
            }, 0);
        } catch (RemoteException ignored) {
        }

        try {
            if (service != null && service.asBinder().isBinderAlive()) {
                Log.v("LorieBroadcastReceiver", "Extracting logcat fd.");
                ParcelFileDescriptor logcatOutput = service.getLogcatOutput();
                if (logcatOutput != null)
                    LorieView.startLogcat(logcatOutput.detachFd());

                tryConnect();

                if (intent != getIntent()) {
              //     getIntent().putExtra(null, bundle);
                    setIntent(intent);
                }
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
        }
    }

    boolean tryConnect() {
        if (LorieView.connected())
            return false;

        if (service == null) {
            boolean sent = LorieView.requestConnection();
            handler.postDelayed(this::tryConnect, 250);
            return true;
        }

        try {
            ParcelFileDescriptor fd = service.getXConnection();
            if (fd != null) {
                Log.v("MainActivity", "Extracting X connection socket.");
                LorieView.connect(fd.detachFd());
                getLorieView().triggerCallback();
                clientConnectedStateChanged();
                getLorieView().reloadPreferences(prefs);
            } else
                handler.postDelayed(this::tryConnect, 250);
        } catch (Exception e) {
            Log.e("MainActivity", "Something went wrong while we were establishing connection", e);
            service = null;

            handler.postDelayed(this::tryConnect, 250);
        }
        return false;
    }

    public void setX11FocusedChanged(boolean x11Focused) {
        FullscreenWorkaround.setX11Focused(x11Focused);
    }

    public boolean getX11Focus() {
        return FullscreenWorkaround.getX11Focused();
    }

     protected void onPreferencesChanged(String key) {
        if ("additionalKbdVisible".equals(key)) {
            return;
        }


        handler.removeCallbacks(this::onPreferencesChangedCallback);
        handler.postDelayed(this::onPreferencesChangedCallback, 100);
    }

    @SuppressLint("UnsafeIntentLaunch")
    void onPreferencesChangedCallback() {
    if (isFinishing()) {
            return;
        }
        if (SDK_INT >= VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) {
            return;
        }
    
        prefs.recheckStoringSecondaryDisplayPreferences();

        onWindowFocusChanged(hasWindowFocus());
        LorieView lorieView = getLorieView();
if (lorieView == null) {
            // Host may be embedded in xodos2: our LorieView is not in this content view.
            return;
        }
        mInputHandler.reloadPreferences(prefs);
        lorieView.reloadPreferences(prefs);

        setTerminalToolbarView();

        lorieView.triggerCallback();

        filterOutWinKey = prefs.filterOutWinkey.get();
        if (prefs.enableAccessibilityServiceAutomatically.get())
            KeyInterceptor.launch(this);
        else if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PERMISSION_GRANTED)
            KeyInterceptor.shutdown(true);

        useTermuxEKBarBehaviour = prefs.useTermuxEKBarBehaviour.get();
        showIMEWhileExternalConnected = prefs.showIMEWhileExternalConnected.get();

        findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && LorieView.connected() ? VISIBLE : View.GONE);
        showMouseAuxButtons(prefs.showMouseHelper.get());
        showStylusAuxButtons(prefs.showStylusClickOverride.get());

        getDisplayTerminalToolbarViewPager().setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get()) / 100);

        lorieView.requestLayout();
        lorieView.invalidate();
        
        
    }


@Override
protected void onStart() {
    super.onStart();
    app.xodos2.X11Runtime.INSTANCE.setLorieInputRouteTopVisible(true);
    startHudIfEnabled();   // start & bind if enabled
}


@Override
protected void onStop() {
app.xodos2.X11Runtime.INSTANCE.setLorieInputRouteTopVisible(false);
    super.onStop();
    app.xodos2.X11Runtime.INSTANCE.setLorieInputRouteTopVisible(false);
    // Unbind but do NOT stop service – let it run in background
    if (isBound) {
        unbindService(hudConnection);
        isBound = false;
    }
}

    @Override
    public void onResume() {
        super.onResume();
        app.xodos2.X11Runtime.INSTANCE.setLorieInputRouteTopVisible(true);
      //  mNotification = buildNotification();
     //   mNotificationManager.notify(mNotificationId, mNotification);
isResumed = true;
    if (isBound && hudService != null) {
        hudService.attachToActivity(this);
    }
        setTerminalToolbarView();
        getLorieView().requestFocus();
    }

        @Override
    public void onPause() {
  //  inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);
app.xodos2.X11Runtime.INSTANCE.setLorieInputRouteTopVisible(false);
        for (StatusBarNotification notification: mNotificationManager.getActiveNotifications())
            if (notification.getId() == mNotificationId)
                mNotificationManager.cancel(mNotificationId);
        
        super.onPause();
        isResumed = false;
if (!isInPictureInPictureMode()) {
    finish();
}
        //prepareToExit();
    if (isBound && hudService != null) {
        hudService.detach();
    }
    stopHudService();  
LogcatLogger.stop();
    }
    public LorieView getLorieView() {
        return findViewById(R.id.lorieView);
    }

    public ViewPager getDisplayTerminalToolbarViewPager() {
        return findViewById(R.id.display_terminal_toolbar_view_pager);
    }

    private void setTerminalToolbarView() {
        final ViewPager pager = getDisplayTerminalToolbarViewPager();
        ViewGroup parent = (ViewGroup) pager.getParent();

        boolean showNow = LorieView.connected() && prefs.showAdditionalKbd.get() && prefs.additionalKbdVisible.get();

        pager.setVisibility(showNow ? VISIBLE : View.INVISIBLE);

        if (showNow) {
            pager.setAdapter(new X11ToolbarViewPager.PageAdapter(this, (v, k, e) -> mInputHandler.sendKeyEvent(e)));
            pager.clearOnPageChangeListeners();
            pager.addOnPageChangeListener(new X11ToolbarViewPager.OnPageChangeListener(this, pager));
            pager.bringToFront();
        } else {
            parent.removeView(pager);
            parent.addView(pager, 0);
            if (mExtraKeys != null)
                mExtraKeys.unsetSpecialKeys();
        }

        ViewGroup.LayoutParams layoutParams = pager.getLayoutParams();
        layoutParams.height = Math.round(37.5f * getResources().getDisplayMetrics().density *
            (TermuxX11ExtraKeys.getExtraKeysInfo() == null ? 0 : TermuxX11ExtraKeys.getExtraKeysInfo().getMatrix().length));
        pager.setLayoutParams(layoutParams);

        frm.setPadding(0, 0, 0, prefs.adjustHeightForEK.get() && showNow ? layoutParams.height : 0);
        getLorieView().requestFocus();
    }

    public void toggleExtraKeys(boolean visible, boolean saveState) {
        boolean enabled = prefs.showAdditionalKbd.get();

        if (enabled && LorieView.connected() && saveState)
            prefs.additionalKbdVisible.put(visible);

        setTerminalToolbarView();
        getWindow().setSoftInputMode(prefs.Reseed.get() ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);
    }

    public void toggleExtraKeys() {
        int visibility = getDisplayTerminalToolbarViewPager().getVisibility();
        toggleExtraKeys(visibility != VISIBLE, true);
        getLorieView().requestFocus();
    }

    public boolean handleKey(KeyEvent e) {
        if (filterOutWinKey && (e.getKeyCode() == KEYCODE_META_LEFT || e.getKeyCode() == KEYCODE_META_RIGHT || e.isMetaPressed()))
            return false;
        mLorieKeyListener.onKey(getLorieView(), e.getKeyCode(), e);
        return true;
    }



    @SuppressLint("ObsoleteSdkInt")
    Notification buildNotification() {
        NotificationCompat.Builder builder =  new NotificationCompat.Builder(this, getNotificationChannel(mNotificationManager))
                .setContentTitle("Termux:X11")
                .setSmallIcon(R.drawable.ic_x11_icon)
                .setContentText(getResources().getText(R.string.notification_content_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setSilent(true)
                .setShowWhen(false)
                .setColor(0xFF607D8B);
        return mInputHandler.setupNotification(prefs, builder).build();
    }
//    int orientation;

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation != orientation) {
            closeSoftKeyboard();
        }

        orientation = newConfig.orientation;
   
        
                  if (termuxActivityListener != null) {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
            boolean forceLandscape = p.getBoolean("forceLandscape", false);
            if (!forceLandscape) {
                termuxActivityListener.onChangeOrientation(newConfig.orientation);
            } else {
                termuxActivityListener.onChangeOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
            handler.postDelayed(() -> {
                getLorieView().regenerate();
            }, 1000);
        }
        
        
        setTerminalToolbarView();
//        Log.d("onConfigurationChanged","orientation:"+orientation);
    }
    

    public int getOrientation() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        int rotation = display.getRotation();

        switch (rotation) {
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            default:
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        KeyInterceptor.recheck();
        prefs.recheckStoringSecondaryDisplayPreferences();
        Window window = getWindow();
        View decorView = window.getDecorView();
        boolean fullscreen = prefs.fullscreen.get();
        boolean hideCutout = prefs.hideCutout.get();
        boolean reseed = prefs.Reseed.get();

        int requestedOrientation;
        switch (prefs.forceOrientation.get()) {
            case "portrait":
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case "landscape":
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case "reverse portrait":
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                break;
            case "reverse landscape":
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                break;
            default:
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }

        if (getRequestedOrientation() != requestedOrientation)
            setRequestedOrientation(requestedOrientation);

        if (hasFocus) {
            if (SDK_INT >= VERSION_CODES.P) {
                if (hideCutout)
                    getWindow().getAttributes().layoutInDisplayCutoutMode = (SDK_INT >= VERSION_CODES.R) ?
                        LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS :
                        LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                else
                    getWindow().getAttributes().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            }

            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
        }

        window.setFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | FLAG_KEEP_SCREEN_ON | FLAG_TRANSLUCENT_STATUS, 0);
        if (hasFocus) {
            if (fullscreen) {
                window.addFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            } else {
                window.clearFlags(FLAG_FULLSCREEN);
                decorView.setSystemUiVisibility(0);
            }
        }

        if (prefs.keepScreenOn.get())
            window.addFlags(FLAG_KEEP_SCREEN_ON);
        else
            window.clearFlags(FLAG_KEEP_SCREEN_ON);

        window.setSoftInputMode(reseed ? SOFT_INPUT_ADJUST_RESIZE : SOFT_INPUT_ADJUST_PAN);

//        ((FrameLayout) findViewById(android.R.id.content)).getChildAt(0).setFitsSystemWindows(!fullscreen);
        if (hasFocus) {
            getLorieView().regenerate();
            getLorieView().requestLayout();
        }
        getLorieView().requestFocus();
    }

    public static boolean hasPipPermission(@NonNull Context context) {
        AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null)
            return false;
        else if (Build.VERSION.SDK_INT >= VERSION_CODES.Q)
            return appOpsManager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        else
            return appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void onUserLeaveHint() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getBoolean("PIP", false) && hasPipPermission(this)) {
            enterPictureInPictureMode();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
//        toggleExtraKeys(!isInPictureInPictureMode, false);

//        frm.setPadding(0, 0, 0, 0);
        this.isInPictureInPictureMode = isInPictureInPictureMode;
        final ViewPager pager = getDisplayTerminalToolbarViewPager();
        pager.setAlpha(isInPictureInPictureMode ? 0.f : ((float) prefs.opacityEKBar.get()) / 100);
        findViewById(R.id.mouse_buttons).setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        findViewById(R.id.mouse_helper_visibility).setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
    }

    /**
     * Manually toggle soft keyboard visibility
     *
     * @param context calling context
     */
    public static void toggleKeyboardVisibility(Context context) {
        Log.d("MainActivity", "Toggling keyboard visibility");
        if (inputMethodManager != null) {
            android.util.Log.d("toggleKeyboardVisibility", "externalKeyboardConnected " + externalKeyboardConnected + " showIMEWhileExternalConnected " + showIMEWhileExternalConnected);
            if (isConnected()) {
                getInstance().getLorieView().requestFocus();
            }
            if (!externalKeyboardConnected || showIMEWhileExternalConnected) {
                openSoftKeyboard();
            } else {
                closeSoftKeyboard();
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    void clientConnectedStateChanged() {
        runOnUiThread(() -> {
            boolean connected = LorieView.connected();
            setTerminalToolbarView();
            findViewById(R.id.mouse_buttons).setVisibility(prefs.showMouseHelper.get() && "1".equals(prefs.touchMode.get()) && connected ? VISIBLE : View.GONE);
            findViewById(R.id.stub).setVisibility(connected ? View.INVISIBLE : VISIBLE);
            getLorieView().setVisibility(connected ? VISIBLE : View.INVISIBLE);
            MainActivity.mLorieViewConnected = connected;

            // We should recover connection in the case if file descriptor for some reason was broken...
            if (!connected) {
                tryConnect();
            } else {
                getLorieView().setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
                openPreference(false);
            }

            onWindowFocusChanged(hasWindowFocus());
        });
    }

    public static boolean isConnected() {
        if (getInstance() == null)
            return false;

        return LorieView.connected();
    }

    public static void getRealMetrics(DisplayMetrics m) {
        LorieView lv = X11ActivityBridge.getLorieView();
        if (lv != null && lv.getDisplay() != null) {
            lv.getDisplay().getRealMetrics(m);
            return;
        }
        MainActivity inst = getInstance();
        if (inst != null && inst.getLorieView() != null && inst.getLorieView().getDisplay() != null)
            inst.getLorieView().getDisplay().getRealMetrics(m);
    }
    public static void setCapturingEnabled(boolean enabled) {
        if (getInstance() == null || getInstance().mInputHandler == null)
            return;

        getInstance().mInputHandler.setCapturingEnabled(enabled);
    }

    public boolean shouldInterceptKeys() {
        View textInput = findViewById(R.id.display_terminal_toolbar_text_input);
        if (mInputHandler == null || !hasWindowFocus() || (textInput != null && textInput.isFocused()))
            return false;

        return mInputHandler.shouldInterceptKeys();
    }

    public void setExternalKeyboardConnected(boolean connected) {
        externalKeyboardConnected = connected;
        EditText textInput = findViewById(R.id.display_terminal_toolbar_text_input);
        if (textInput != null)
            textInput.setShowSoftInputOnFocus(!connected || showIMEWhileExternalConnected);
        if (connected && !showIMEWhileExternalConnected)
            inputMethodManager.hideSoftInputFromWindow(getWindow().getDecorView().getRootView().getWindowToken(), 0);
        getLorieView().requestFocus();
    }

    private void showStylusAuxButtons(boolean show) {
        LinearLayout buttons = findViewById(R.id.mouse_helper_visibility);
        if (LorieView.connected() && show) {
            buttons.setVisibility(VISIBLE);
            buttons.setAlpha(isInPictureInPictureMode ? 0.f : 1.f);
        } else {
            //Reset default input back to normal
            TouchInputHandler.STYLUS_INPUT_HELPER_MODE = 1;
            final float menuUnselectedTrasparency = 0.66f;
            final float menuSelectedTrasparency = 1.0f;
            findViewById(R.id.button_left_click).setAlpha(menuSelectedTrasparency);
            findViewById(R.id.button_right_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_middle_click).setAlpha(menuUnselectedTrasparency);
            findViewById(R.id.button_visibility).setAlpha(menuUnselectedTrasparency);
            buttons.setVisibility(View.GONE);
        }
    }

    private void makeSureHelpersAreVisibleAndInScreenBounds() {
        final ViewPager pager = getDisplayTerminalToolbarViewPager();
        View mouseAuxButtons = findViewById(R.id.mouse_buttons);
        View stylusAuxButtons = findViewById(R.id.mouse_helper_visibility);
        int maxYDecrement = (pager.getVisibility() == VISIBLE) ? pager.getHeight() : 0;

        mouseAuxButtons.setX(MathUtils.clamp(mouseAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - mouseAuxButtons.getWidth()));
        mouseAuxButtons.setY(MathUtils.clamp(mouseAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - mouseAuxButtons.getHeight() - maxYDecrement));

        stylusAuxButtons.setX(MathUtils.clamp(stylusAuxButtons.getX(), frm.getX(), frm.getX() + frm.getWidth() - stylusAuxButtons.getWidth()));
        stylusAuxButtons.setY(MathUtils.clamp(stylusAuxButtons.getY(), frm.getY(), frm.getY() + frm.getHeight() - stylusAuxButtons.getHeight() - maxYDecrement));
    }

    public void toggleStylusAuxButtons() {
        showStylusAuxButtons(findViewById(R.id.mouse_helper_visibility).getVisibility() != VISIBLE);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    private void showMouseAuxButtons(boolean show) {
        View v = findViewById(R.id.mouse_buttons);
        v.setVisibility((LorieView.connected() && show && "1".equals(prefs.touchMode.get())) ? VISIBLE : View.GONE);
        v.setAlpha(isInPictureInPictureMode ? 0.f : 0.7f);
        makeSureHelpersAreVisibleAndInScreenBounds();
    }

    public void toggleMouseAuxButtons() {
        showMouseAuxButtons(findViewById(R.id.mouse_buttons).getVisibility() != VISIBLE);
    }

    public void showProcessManagerDialog() {
    // Check if activity is still valid
    if (this == null || isFinishing() || isDestroyed()) {
        
        return;
    }
    
    try {
        TaskManagerDialog dialog = new TaskManagerDialog(this);
        dialog.show();
    } catch (WindowManager.BadTokenException e) {
        
    }
}

    //whether view include (x,y)
    private boolean isTouchPointInView(View view, int x, int y) {
        if (view == null) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int left = location[0];
        int top = location[1];
        int right = left + view.getMeasuredWidth();
        int bottom = top + view.getMeasuredHeight();
        //view.isClickable() &&
        if (y >= top && y <= bottom && x >= left
            && x <= right) {
            return true;
        }
        return false;
    }

    protected boolean extraKeyboardHandleTouchEvent(MotionEvent event) {
        if (getDisplayTerminalToolbarViewPager().getVisibility() != VISIBLE) {
            return false;
        }
        return isTouchPointInView((View) getDisplayTerminalToolbarViewPager(), (int) event.getRawX(), (int) event.getRawY());
    }
    
    // ================== NEW REAL PROCESS LISTING METHODS ==================
    /**
     * Returns a list of all processes owned by this app's UID.
     * Uses /proc filesystem (no root required).
     */
    private List<ProcessInfo> getAndroidProcessList() {
        List<ProcessInfo> list = new ArrayList<>();
        int myUid = android.os.Process.myUid();
        File proc = new File("/proc");
        File[] files = proc.listFiles();
        if (files == null) return list;

        for (File file : files) {
            if (!file.isDirectory()) continue;
            String name = file.getName();
            if (!name.matches("\\d+")) continue; // only numeric PIDs
            int pid = Integer.parseInt(name);

            ProcessInfo info = readProcessInfo(pid, myUid);
            if (info != null) list.add(info);
        }
        return list;
    }

    /**
     * Reads process info from /proc/[pid]/status and /proc/[pid]/statm.
     * Returns null if process is not owned by myUid or if status cannot be read.
     */
    private ProcessInfo readProcessInfo(int pid, int myUid) {
        // Read UID and process name from /proc/[pid]/status
        File statusFile = new File("/proc/" + pid + "/status");
        if (!statusFile.exists()) return null;

        String procName = null;
        int uid = -1;

        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.split("\\s+");
                    uid = Integer.parseInt(parts[1]); // real UID
                    if (uid != myUid) return null;    // filter by our UID
                } else if (line.startsWith("Name:")) {
                    procName = line.substring(5).trim();
                }
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }

        if (procName == null) procName = "unknown";

        // Read memory (RSS) from /proc/[pid]/statm
        long memoryBytes = 0;
        File statmFile = new File("/proc/" + pid + "/statm");
        if (statmFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(statmFile))) {
                String[] parts = reader.readLine().split("\\s+");
                if (parts.length >= 2) {
                    long pages = Long.parseLong(parts[1]); // RSS in pages
                    memoryBytes = pages * 4096; // assume 4KB page size
                } else if (parts.length >= 1) {
                    long pages = Long.parseLong(parts[0]); // total program size
                    memoryBytes = pages * 4096;
                }
            } catch (IOException | NumberFormatException ignored) {}
        }

        // affinityMask: set to all CPUs (irrelevant for Android processes but required by constructor)
        int numCores = Runtime.getRuntime().availableProcessors();
        int affinityMask = (1 << numCores) - 1; // all CPUs

        return new ProcessInfo(pid, procName, memoryBytes, affinityMask, false);
    }
    // ======================================================================

    // Add this class inside MainActivity.java (but outside MainActivity class)
public static class DrawerPreferenceFragment extends PreferenceFragmentCompat 
        implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {

    private MainActivity activity;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        activity = (MainActivity) context;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.drawer_preferences, rootKey);

        // Set listeners for all preferences
        PreferenceScreen screen = getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference p = screen.getPreference(i);
            if (p instanceof PreferenceCategory) {
                PreferenceCategory category = (PreferenceCategory) p;
                for (int j = 0; j < category.getPreferenceCount(); j++) {
                    Preference child = category.getPreference(j);
                    child.setOnPreferenceClickListener(this);
                    child.setOnPreferenceChangeListener(this);
                }
            } else {
                p.setOnPreferenceClickListener(this);
                p.setOnPreferenceChangeListener(this);
            }
        }

        // Sync HUD switch with actual service state (optional)
        SwitchPreferenceCompat hudSwitch = findPreference("hud_enabled");
        if (hudSwitch != null) {
            // You could check if HudService is running and set the switch accordingly
            // This requires a way to query the service, e.g., a static boolean in HudService
            // hudSwitch.setChecked(HudService.isRunning());
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (key == null) return false;

        switch (key) {
            case "full_settings":
                Intent settingsIntent = new Intent(activity, LoriePreferences.class);
                activity.startActivity(settingsIntent);
                activity.drawerLayout.closeDrawer(GravityCompat.START);
                return true;

            case "open_keyboard":
                activity.drawerLayout.closeDrawer(GravityCompat.START);
                MainActivity.toggleKeyboardVisibility(activity);
                return true;

            case "select_controller":
                activity.drawerLayout.closeDrawer(GravityCompat.START);
                activity.showInputControlsDialog();
                return true;

            case "open_progress_manager":
                activity.showProcessManagerDialog();
                return true;

            case "install_x11_server_bridge":
                activity.installX11ServerBridge();
                return true;

            case "stop_desktop":
                activity.stopDesktop();
                return true;

            case "start_debug":
                activity.drawerLayout.closeDrawer(GravityCompat.START);
                activity.startDebugMode();
                return true;

            case "help":
                openHelpUrl();
                return true;

            case "exit":
               // System.exit(0);
                activity.finish();
                return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        if (key == null) return false;

        switch (key) {
            case "hud_enabled":   // must match the key in XML
                boolean enable = (Boolean) newValue;
                if (enable) {
                    startHudService();
                } else {
                    stopHudService();
                }
                // Return true to accept the change (the switch will update)
                return true;
        }
        return false;
    }

    private void startHudService() {
    activity.startHudService();
    Toast.makeText(activity, "HUD started", Toast.LENGTH_SHORT).show();
    activity.drawerLayout.closeDrawer(GravityCompat.START);
}

private void stopHudService() {
    activity.stopHudService();    // <-- use activity's method
    Toast.makeText(activity, "HUD stopped", Toast.LENGTH_SHORT).show();
    activity.drawerLayout.closeDrawer(GravityCompat.START);
}

    private void openHelpUrl() {
        try {
            Intent helpIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/termux/termux-x11/blob/master/README.md#running-graphical-applications"));
            activity.startActivity(helpIntent);
            activity.drawerLayout.closeDrawer(GravityCompat.START);
        } catch (Exception e) {
            Toast.makeText(activity, "Cannot open browser", Toast.LENGTH_SHORT).show();
            Log.e("DrawerPreferenceFragment", "Error opening help URL", e);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(@NonNull Preference preference) {
        return onPreferenceClick(preference);
    }
}

}