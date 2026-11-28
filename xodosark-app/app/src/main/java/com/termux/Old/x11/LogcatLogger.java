package com.termux.x11;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

public class LogcatLogger {

    private static Thread loggerThread;
    private static volatile boolean running = false;
    private static java.lang.Process logcatProcess;
    private static FileWriter writer;

    public static synchronized void start(Context context) {
        if (running) return;

        running = true;

        int myPid = Process.myPid();

        loggerThread = new Thread(() -> {
            try {
                File dir;
                if (Build.VERSION.SDK_INT >= 29) {
                    dir = new File(context.getExternalFilesDir(null), "logs");
                } else {
                    dir = new File(Environment.getExternalStorageDirectory(), "xodos/logs");
                }

                if (!dir.exists()) dir.mkdirs();

                File logFile = new File(dir, "app.log");
                writer = new FileWriter(logFile, true);

                Runtime.getRuntime().exec("logcat -c");

                logcatProcess = Runtime.getRuntime().exec(
                        new String[]{"logcat", "-v", "time"}
                );

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(logcatProcess.getInputStream())
                );

                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (!running) break;

                    if (line.contains(String.valueOf(myPid))) {
                        writer.write(line + "\n");
                        writer.flush();
                    }
                }

            } catch (Exception e) {
                Log.e("LogcatLogger", "Error", e);
            } finally {
                cleanup();
            }
        });

        loggerThread.start();
    }

    public static synchronized void stop() {
        running = false;
        cleanup();
    }

    private static void cleanup() {
        try {
            if (logcatProcess != null) {
                logcatProcess.destroy();
                logcatProcess = null;
            }
        } catch (Exception ignored) {}

        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        } catch (Exception ignored) {}

        if (loggerThread != null) {
            loggerThread.interrupt();
            loggerThread = null;
        }
    }
}