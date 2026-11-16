package com.miroir;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ErrorReporter {

    private static final String TAG        = "ErrorReporter";
    private static final String CHANNEL_ID = "ScreenStreamErrors";
    private static final int    NOTIF_ID_BASE = 99;
    private static final int    MAX_ERRORS = 20;

    // A shared notification ID meant every new error silently replaced whatever
    // error notification was already showing, so anything short of the very last
    // one never reached the user. Each error now gets its own ID so they stack.
    private static final java.util.concurrent.atomic.AtomicInteger notifIdCounter =
        new java.util.concurrent.atomic.AtomicInteger(NOTIF_ID_BASE);

    public enum Level  { INFO, WARNING, ERROR, FATAL }

    public enum Source {
        SCREEN_CAPTURE("Screen Capture"),
        VIRTUAL_DISPLAY("Virtual Display"),
        IMAGE_ENCODE("Image Encoder"),
        AUDIO_CAPTURE("Audio Capture"),
        HTTP_SERVER("HTTP Server"),
        NETWORK("Network"),
        DISPLAY_CHANGE("Display Change"),
        PERMISSION("Permission"),
        SYSTEM("System");

        public final String label;
        Source(String label) { this.label = label; }
    }

    public static class AppError {
        public final long   timestamp;
        public final Level  level;
        public final Source source;
        public final String message;
        public final String detail;

        AppError(Level level, Source source, String message, String detail) {
            this.timestamp = System.currentTimeMillis();
            this.level     = level;
            this.source    = source;
            this.message   = message;
            this.detail    = detail;
        }

        String toJson() {
            return "{\"ts\":" + timestamp
                + ",\"level\":\"" + jsonEscape(level.name()) + "\""
                + ",\"source\":\"" + jsonEscape(source.label) + "\""
                + ",\"msg\":\"" + jsonEscape(message) + "\""
                + ",\"detail\":\"" + jsonEscape(detail != null ? detail : "") + "\"}";
        }

        private static String jsonEscape(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 16);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n");  break;
                    case '\r': sb.append("\\r");  break;
                    case '\t': sb.append("\\t");  break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            return sb.toString();
        }
    }

    public interface ErrorListener { void onError(AppError error); }
    public interface SsePusher     { void push(String json); }

    private static volatile ErrorReporter instance;
    private WeakReference<Context> contextRef;
    private volatile SsePusher ssePusher;
    private final CopyOnWriteArrayList<AppError>      recentErrors = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ErrorListener> listeners    = new CopyOnWriteArrayList<>();

    private ErrorReporter() {}

    public static ErrorReporter get() {
        if (instance == null) {
            synchronized (ErrorReporter.class) {
                if (instance == null) instance = new ErrorReporter();
            }
        }
        return instance;
    }

    public void init(Context ctx) {
        this.contextRef = new WeakReference<>(ctx.getApplicationContext());
        createNotificationChannel();
        installUncaughtExceptionHandler();
    }

    public void setSsePusher(SsePusher p)    { ssePusher = p; }
    public void addListener(ErrorListener l) { listeners.add(l); }
    public List<AppError> getRecentErrors()  { return Collections.unmodifiableList(recentErrors); }

    public void report(Level level, Source source, String message) {
        report(level, source, message, (String) null);
    }

    public void report(Level level, Source source, String message, Throwable t) {
        report(level, source, message, t != null ? throwableToString(t) : null);
    }

    public void report(Level level, Source source, String message, String detail) {
        AppError error = new AppError(level, source, message, detail);

        String log = "[" + source.label + "] " + message + (detail != null ? "\n" + detail : "");
        switch (level) {
            case INFO:    Log.i(TAG, log); break;
            case WARNING: Log.w(TAG, log); break;
            case ERROR:   Log.e(TAG, log); break;
            case FATAL:   Log.e(TAG, "FATAL: " + log); break;
        }

        recentErrors.add(error);
        while (recentErrors.size() > MAX_ERRORS) recentErrors.remove(0);

        if (level == Level.ERROR || level == Level.FATAL) showNotification(error);

        for (ErrorListener l : listeners) {
            try { l.onError(error); } catch (Exception ignored) {}
        }

        SsePusher pusher = ssePusher;
        if (pusher != null) {
            try { pusher.push("{\"type\":\"error\",\"error\":" + error.toJson() + "}"); }
            catch (Exception ignored) {}
        }
    }

    public void info(Source s, String msg)              { report(Level.INFO,    s, msg); }
    public void warn(Source s, String msg)              { report(Level.WARNING, s, msg); }
    public void warn(Source s, String msg, Throwable t) { report(Level.WARNING, s, msg, t); }
    public void error(Source s, String msg)             { report(Level.ERROR,   s, msg); }
    public void error(Source s, String msg, Throwable t){ report(Level.ERROR,   s, msg, t); }
    public void fatal(Source s, String msg)             { report(Level.FATAL,   s, msg); }
    public void fatal(Source s, String msg, Throwable t){ report(Level.FATAL,   s, msg, t); }

    private void installUncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                report(Level.FATAL, Source.SYSTEM,
                    "Uncaught exception on thread [" + thread.getName() + "]: " + throwable.getMessage(),
                    throwable);
            } catch (Exception ignored) {}
            if (original != null) original.uncaughtException(thread, throwable);
        });
    }

    private void createNotificationChannel() {
        Context ctx = contextRef != null ? contextRef.get() : null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ctx != null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "ScreenStream Errors", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Error alerts for ScreenStream");
            NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void showNotification(AppError error) {
        Context ctx = contextRef != null ? contextRef.get() : null;
        if (ctx == null) return;
        try {
            Intent openIntent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
if (openIntent == null) return;

            openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent openPi = PendingIntent.getActivity(ctx, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String copyText = "[" + error.source.label + " / " + error.level.name() + "]\n"
                + error.message
                + (error.detail != null ? "\n\n" + error.detail : "");

            Intent copyIntent = new Intent(ctx, CopyErrorReceiver.class);
            copyIntent.putExtra("error_text", copyText);
            PendingIntent copyPi = PendingIntent.getBroadcast(ctx, error.hashCode(), copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String detail = error.detail != null ? "\n\n" + error.detail : "";

            nm.notify(notifIdCounter.incrementAndGet(), new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("ScreenStream: " + error.source.label + " error")
                .setContentText(error.message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(error.message + detail))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_share, "Copy", copyPi)
                .setAutoCancel(true)
                .build());
        } catch (Exception e) {
            Log.e(TAG, "Failed to show error notification: " + e.getMessage());
        }
    }

    private static String throwableToString(Throwable t) {
        if (t == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter(256);
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
