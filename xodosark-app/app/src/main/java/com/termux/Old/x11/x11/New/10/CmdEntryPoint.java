package com.termux.x11;

import static android.system.Os.getuid;
import static android.system.Os.getenv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Keep;

import app.xodos2.BuildConfig;
import dalvik.annotation.optimization.CriticalNative;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;

@Keep
@SuppressLint({"StaticFieldLeak", "UnsafeDynamicallyLoadedCode"})
public class CmdEntryPoint extends ICmdEntryInterface.Stub {
    public static final String ACTION_START = "com.termux.x11.CmdEntryPoint.ACTION_START";
    public static final String EXTRA_DOCUMENT_TAG = "document_tag";
    static Handler handler;
    public static Context ctx;
    private final Intent intent = createIntent();
    private boolean broadcastPending;

    /**
     * Entry point invoked by X11ServerService (in :x11) or standalone app_process.
     */
    public static void main(String[] args) {
        initEntryPoint();
        Log.i("CmdEntryPoint", "commit " + BuildConfig.COMMIT);
        handler.post(() -> new CmdEntryPoint(args));
        Looper.loop();
    }

    CmdEntryPoint(String[] args) {
        if (!start(args)) {
            Log.e("CmdEntryPoint", "native start() failed (args=" + java.util.Arrays.toString(args) + ")");
            // In standalone app_process, exit on failure; inside XoDos (:x11), avoid killing the service loop
            if (!"app.xodos2".equals(BuildConfig.APPLICATION_ID)) {
                System.exit(1);
            }
            return;
        }

        // Kick off initial broadcast loop so MainActivity receives the IBinder immediately
        sendBroadcastDelayed();
    }

    // Called from native once OsVendorInit() resolves the "-tag" argument, if any.
    @Keep
    void setDocumentTag(String tag) {
        if (tag != null) {
            intent.putExtra(EXTRA_DOCUMENT_TAG, tag);
        }
    }

    @SuppressLint({"WrongConstant", "PrivateApi"})
    private Intent createIntent() {
        String targetPackage = getenv("TERMUX_X11_OVERRIDE_PACKAGE");
        if (targetPackage == null || targetPackage.isEmpty()) {
            targetPackage = BuildConfig.APPLICATION_ID; // "app.xodos2"
        }

        Bundle bundle = new Bundle();
        bundle.putBinder(null, this);

        Intent intent = new Intent(ACTION_START);
        intent.putExtra(null, bundle);
        intent.setPackage(targetPackage);

        if (getuid() == 0 || getuid() == 2000) {
            intent.setFlags(0x00400000 /* FLAG_RECEIVER_FROM_SHELL */);
        }

        return intent;
    }

    // Called from native (the X server thread) on every knock on the port;
    // coalesces bursts into a single broadcast fired 250ms later.
    @Keep
    private void sendBroadcast() {
        synchronized (this) {
            if (broadcastPending)
                return;
            broadcastPending = true;
        }
        handler.postDelayed(() -> {
            synchronized (this) {
                broadcastPending = false;
            }
            sendBroadcast(intent);
        }, 250);
    }

    // Called from native start() and periodically until MainActivity connects.
    @Keep
    private void sendBroadcastDelayed() {
        if (!connected()) {
            sendBroadcast(intent);
        }
        handler.postDelayed(this::sendBroadcastDelayed, 1000);
    }

    static void sendBroadcast(Intent intent) {
        if (ctx == null) {
            Log.e("Broadcast", "Context is null; cannot send " + intent.getAction());
            return;
        }
        try {
            ctx.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e("Broadcast", "sendBroadcast failed for " + intent.getAction(), e);
        }
    }

    /** Fallback only when launched via CLI app_process without X11ServerService */
    @SuppressLint("DiscouragedPrivateApi")
    public static Context createContext() {
        Context context;
        PrintStream err = System.err;
        try {
            java.lang.reflect.Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            System.setErr(new PrintStream(new OutputStream() { public void write(int arg0) {} }));
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread;
            if (System.getenv("OLD_CONTEXT") != null) {
                activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
            } else {
                activityThread = Class.forName("sun.misc.Unsafe")
                        .getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, activityThreadClass);
            }
            context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
        } catch (Exception e) {
            Log.e("Context", "Failed to instantiate context:", e);
            context = null;
        } finally {
            System.setErr(err);
        }
        return context;
    }

    public native boolean start(String[] args);
    public native void windowChanged(Surface surface);
    public native void cursorWindowChanged(Surface surface);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    @Keep
    public native void reportFatalError(String message);
    @CriticalNative
    private static native boolean connected();

    private static void initEntryPoint() {
        try {
            if (Looper.myLooper() == null && Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Something went wrong when preparing Looper", e);
        }

        Looper l = Looper.myLooper();
        handler = new Handler(l != null ? l : Looper.getMainLooper());

        // Preserve the Application Context passed by X11ServerService; only use Unsafe fallback for CLI
        if (ctx == null) {
            ctx = createContext();
        }

        try {
            System.loadLibrary("Xlorie");
        } catch (UnsatisfiedLinkError first) {
            String abi = (android.os.Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0]);
            String subPath = "lib/" + abi + "/libXlorie.so";
            ClassLoader loader = CmdEntryPoint.class.getClassLoader();
            URL res = loader != null ? loader.getResource(subPath) : null;
            String libPath = res != null ? res.getFile().replace("file:", "") : null;
            if (libPath == null) {
                Log.e("CmdEntryPoint", "Failed to load libXlorie (no ClassLoader path)", first);
                System.exit(134);
            } else {
                try {
                    System.load(libPath);
                } catch (UnsatisfiedLinkError | Exception e) {
                    Log.e("CmdEntryPoint", "Failed to dlopen " + libPath, e);
                    System.exit(134);
                }
            }
        }
    }
}
