package com.miroir;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

public class MiroirService extends Service {

    private static final String CHANNEL_ID    = "ScreenStreamChannel";
    private static final int    NOTIFICATION_ID = 1;
    private static final int    MAX_WIDTH     = 1280;

    public static final String ACTION_START      = "ACTION_START";
    public static final String ACTION_STOP       = "ACTION_STOP";
    public static final String ACTION_STOPPED    = "com.miroir.ACTION_STOPPED";
    public static final String EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE";
    public static final String EXTRA_DATA        = "EXTRA_DATA";
    public static final String EXTRA_PORT        = "EXTRA_PORT";
    public static final String EXTRA_QUALITY     = "EXTRA_QUALITY";
    public static final String EXTRA_FPS         = "EXTRA_FPS";
    public static final String EXTRA_AUDIO       = "EXTRA_AUDIO";
    public static final String EXTRA_AUDIO_SR    = "EXTRA_AUDIO_SR";
    public static final String EXTRA_AUDIO_CH    = "EXTRA_AUDIO_CH";
    public static final String EXTRA_AUDIO_ENC   = "EXTRA_AUDIO_ENC";
    public static final String EXTRA_AUTH_MODE   = "EXTRA_AUTH_MODE";
    public static final String EXTRA_PIN         = "EXTRA_PIN";
    public static final String EXTRA_BASIC_USER  = "EXTRA_BASIC_USER";
    public static final String EXTRA_BASIC_PASS  = "EXTRA_BASIC_PASS";
    public static final String EXTRA_HTTPS       = "EXTRA_HTTPS";
    public static final String EXTRA_KEEP_AWAKE  = "EXTRA_KEEP_AWAKE";

    public static final String EXTRA_MAX_WIDTH  = "EXTRA_MAX_WIDTH";
    public static final String EXTRA_MAX_HEIGHT = "EXTRA_MAX_HEIGHT";
    public static final String EXTRA_BITRATE = "EXTRA_BITRATE";

    // v2: the original key was generated declaring three digests (SHA-256/384/512).
    // Some KeyMint/StrongBox TEE implementations (confirmed via logcat on a real
    // device: Keystore2 threw Error::Km(INCOMPATIBLE_DIGEST) from inside Conscrypt's
    // RSA sign call during the TLS handshake, every single time, for every digest
    // tried) encode a multi-digest RSA signing key in a way that's unusable at sign
    // time even though key generation itself succeeds. ensureHttpsKeyExists() only
    // (re)generates a key if the alias doesn't already exist, so an app update alone
    // never replaced an already-broken key with a fresh single-digest one - the
    // alias needs to change too, so every device gets a new key under the current
    // (single-digest) KeyGenParameterSpec below.
    private static final String HTTPS_KEY_ALIAS = "airscreen_https_key_v5";

    public enum AuthMode { NONE, PIN, BASIC }

    private static final AtomicInteger  jpegQuality     = new AtomicInteger(70);
    private static final AtomicInteger  targetFps       = new AtomicInteger(24);
    private static final AtomicBoolean  audioEnabled    = new AtomicBoolean(true);
    private static final AtomicBoolean  httpsEnabled    = new AtomicBoolean(false);
    private static final AtomicInteger  audioSampleRate = new AtomicInteger(44100);
    private static final AtomicInteger  audioChannels   = new AtomicInteger(2);
    private static final AtomicInteger  audioEncoding   = new AtomicInteger(android.media.AudioFormat.ENCODING_PCM_16BIT);

    private static final AtomicInteger  maxWidth      = new AtomicInteger(1280);
    private static final AtomicInteger  maxHeight     = new AtomicInteger(720);
    private static final AtomicInteger  bitrate       = new AtomicInteger(4000); // kbps

    private static final AtomicReference<AuthMode> authMode    = new AtomicReference<>(AuthMode.NONE);
    private static final AtomicReference<String>   requiredPin = new AtomicReference<>("");
    private static final AtomicReference<String>   basicUser   = new AtomicReference<>("");
    private static final AtomicReference<String>   basicPass   = new AtomicReference<>("");

    private static final AtomicReference<byte[]> latestFrame  = new AtomicReference<>(null);
    private static final AtomicInteger           streamWidth  = new AtomicInteger(0);
    private static final AtomicInteger           streamHeight = new AtomicInteger(0);
    private static final AtomicInteger           sessionId    = new AtomicInteger(0);

    private final CopyOnWriteArrayList<OutputStream> videoClients = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OutputStream> sseClients   = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<OutputStream> audioClients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean                      rebuilding   = new AtomicBoolean(false);

    private MediaProjection mediaProjection;
    private VirtualDisplay  virtualDisplay;
    private ImageReader     imageReader;
    private HandlerThread   imageThread;
    private Handler         imageHandler;
    private AudioRecord     audioRecord;
    private Thread          audioThread;
    private ServerSocket    serverSocket;
    private Thread          serverThread;
    private Thread          frameDispatchThread;
    private volatile boolean running = false;
    private volatile int    currentAudioBits = 16;

    private int    capWidth, capHeight, capDpi;
    private int    savedResultCode;
    private Intent savedData;
    private int    savedPort = 8080;

    private ExecutorService     rebuildExecutor;
    private final AtomicBoolean rebuildPending = new AtomicBoolean(false);
    private volatile int        lastKnownW = 0, lastKnownH = 0;
    private DisplayManager      displayManager;
    private PowerManager.WakeLock wakeLock;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) {}
        @Override public void onDisplayRemoved(int id) {}
        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return;
            try {
                Display d = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                if (d == null) {
                    ErrorReporter.get().warn(ErrorReporter.Source.DISPLAY_CHANGE,
                        "onDisplayChanged: getDisplay returned null");
                    return;
                }
                DisplayMetrics m = new DisplayMetrics();
                d.getRealMetrics(m);
                int w = m.widthPixels, h = m.heightPixels;
                if (w == lastKnownW && h == lastKnownH) return;
                if (w <= 0 || h <= 0) {
                    ErrorReporter.get().warn(ErrorReporter.Source.DISPLAY_CHANGE,
                        "Invalid display metrics: " + w + "x" + h);
                    return;
                }
                lastKnownW = w;
                lastKnownH = h;
                if (rebuildPending.compareAndSet(false, true)) {
                    ErrorReporter.get().info(ErrorReporter.Source.DISPLAY_CHANGE,
                        "Display changed to " + w + "x" + h);
                    rebuildExecutor.submit(() -> safeRebuild(w, h));
                }
            } catch (Exception e) {
                ErrorReporter.get().error(ErrorReporter.Source.DISPLAY_CHANGE,
                    "onDisplayChanged error: " + e.getMessage(), e);
            }
        }
    };

    public static void setJpegQuality(int q)        { jpegQuality.set(q); }
    public static void setTargetFps(int fps)        { targetFps.set(fps); }
    public static void setAudioEnabled(boolean on)  { audioEnabled.set(on); }
    public static void setHttpsEnabled(boolean on)  { httpsEnabled.set(on); }
    public static void setAudioSampleRate(int sr)   { audioSampleRate.set(sr); }
    public static void setAudioChannels(int ch)     { audioChannels.set(ch); }
    public static void setAudioEncoding(int enc)    { audioEncoding.set(enc); }
    public static void setAuthMode(AuthMode mode)   { authMode.set(mode != null ? mode : AuthMode.NONE); }
    public static void setRequiredPin(String pin)   { requiredPin.set(pin != null ? pin : ""); }
    public static void setBasicCredentials(String user, String pass) {
        basicUser.set(user != null ? user : "");
        basicPass.set(pass != null ? pass : "");
    }

    private static AuthMode parseAuthMode(String s) {
        try { return AuthMode.valueOf(s); } catch (Exception e) { return AuthMode.NONE; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ErrorReporter.get().init(this);
        ErrorReporter.get().setSsePusher(this::pushSseEvent);
        createNotificationChannel();
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        rebuildExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DisplayRebuild");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        String action = (intent != null) ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(savedPort > 0 ? savedPort : 8080));

        if (intent == null) {
            ErrorReporter.get().warn(ErrorReporter.Source.SYSTEM, "onStartCommand received null intent");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            savedResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED);
            savedData       = intent.getParcelableExtra(EXTRA_DATA);
            savedPort       = intent.getIntExtra(EXTRA_PORT, 8080);
            if (savedResultCode == android.app.Activity.RESULT_CANCELED || savedData == null) {
                ErrorReporter.get().error(ErrorReporter.Source.PERMISSION,
                    "Screen capture permission was denied or the system did not return a valid token.");
                stopSelf();
                return START_NOT_STICKY;
            }
            jpegQuality.set(intent.getIntExtra(EXTRA_QUALITY, 70));
            targetFps.set(intent.getIntExtra(EXTRA_FPS, 24));
            audioEnabled.set(intent.getBooleanExtra(EXTRA_AUDIO, true));
            httpsEnabled.set(intent.getBooleanExtra(EXTRA_HTTPS, false));
            audioSampleRate.set(intent.getIntExtra(EXTRA_AUDIO_SR, 44100));
            audioChannels.set(intent.getIntExtra(EXTRA_AUDIO_CH, 2));
            audioEncoding.set(intent.getIntExtra(EXTRA_AUDIO_ENC, android.media.AudioFormat.ENCODING_PCM_16BIT));
            setAuthMode(parseAuthMode(intent.getStringExtra(EXTRA_AUTH_MODE)));
            setRequiredPin(intent.getStringExtra(EXTRA_PIN));
            setBasicCredentials(intent.getStringExtra(EXTRA_BASIC_USER), intent.getStringExtra(EXTRA_BASIC_PASS));

            if (intent.getBooleanExtra(EXTRA_KEEP_AWAKE, true)) acquireWakeLock();

            maxWidth.set(intent.getIntExtra(EXTRA_MAX_WIDTH, 1280));
            maxHeight.set(intent.getIntExtra(EXTRA_MAX_HEIGHT, 720));
            bitrate.set(intent.getIntExtra(EXTRA_BITRATE, 4000));

            startForeground(NOTIFICATION_ID, buildNotification(savedPort));

            new Thread(() -> startCapture(savedPort), "CaptureStart").start();
        } else {
            ErrorReporter.get().warn(ErrorReporter.Source.SYSTEM, "Unknown action: " + action);
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startCapture(int port) {
        running = true;
        int sid = sessionId.incrementAndGet();

        try {
            imageThread = new HandlerThread("ImageCapture");
            imageThread.start();
            imageHandler = new Handler(imageThread.getLooper());
        } catch (Exception e) {
            ErrorReporter.get().fatal(ErrorReporter.Source.SYSTEM,
                "Failed to start image handler thread", e);
            running = false;
            stopSelf();
            return;
        }

        try {
            MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (mpm == null) throw new IllegalStateException("MediaProjectionManager unavailable");
            mediaProjection = mpm.getMediaProjection(savedResultCode, savedData);
            if (mediaProjection == null) throw new IllegalStateException("getMediaProjection returned null");
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    ErrorReporter.get().warn(ErrorReporter.Source.SCREEN_CAPTURE,
                        "MediaProjection stopped by system");
                    stopCapture();
                    sendBroadcast(new Intent(ACTION_STOPPED).setPackage(getPackageName()));
                    stopSelf();
                }
            }, imageHandler);
        } catch (Exception e) {
            ErrorReporter.get().fatal(ErrorReporter.Source.SCREEN_CAPTURE,
                "Failed to acquire MediaProjection: " + e.getMessage(), e);
            running = false;
            stopSelf();
            return;
        }

        displayManager.registerDisplayListener(displayListener, imageHandler);

        try {
            buildImageReader();
        } catch (Exception e) {
            ErrorReporter.get().fatal(ErrorReporter.Source.SCREEN_CAPTURE,
                "Failed to build ImageReader: " + e.getMessage(), e);
            stopCapture(); stopSelf(); return;
        }

        try {
            buildVirtualDisplay();
        } catch (Exception e) {
            ErrorReporter.get().fatal(ErrorReporter.Source.VIRTUAL_DISPLAY,
                "Failed to create VirtualDisplay: " + e.getMessage(), e);
            stopCapture(); stopSelf(); return;
        }

        try {
            if (serverSocket == null || serverSocket.isClosed()) startHttpServer(port);
        } catch (Exception e) {
            ErrorReporter.get().error(ErrorReporter.Source.HTTP_SERVER,
                "HTTP server failed to start on port " + port + ": " + e.getMessage(), e);
        }

        try {
            startFrameDispatch();
        } catch (Exception e) {
            ErrorReporter.get().error(ErrorReporter.Source.SCREEN_CAPTURE,
                "Frame dispatch thread failed: " + e.getMessage(), e);
        }

        if (audioEnabled.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startAudioCapture();
        } else if (audioEnabled.get()) {
            ErrorReporter.get().info(ErrorReporter.Source.AUDIO_CAPTURE,
                "Audio requires Android 10+ (current API=" + Build.VERSION.SDK_INT + ")");
        }

        pushSseEvent("{\"type\":\"session\",\"id\":" + sid + ",\"w\":" + capWidth + ",\"h\":" + capHeight + "}");
        ErrorReporter.get().info(ErrorReporter.Source.SCREEN_CAPTURE,
            "Session " + sid + ": " + capWidth + "x" + capHeight + " @" + targetFps.get() + "fps");
    }

    private void safeRebuild(int newRawW, int newRawH) {
        try {
            if (!running) return;
            rebuilding.set(true);

            if (imageHandler != null) {
                CountDownLatch latch = new CountDownLatch(1);
                imageHandler.post(latch::countDown);
                if (!latch.await(500, TimeUnit.MILLISECONDS)) {
                    ErrorReporter.get().warn(ErrorReporter.Source.DISPLAY_CHANGE,
                        "imageHandler drain timed out");
                }
            }

            int longEdge  = Math.max(newRawW, newRawH);
            int shortEdge = Math.min(newRawW, newRawH);
            if (longEdge > MAX_WIDTH) {
                float scale = (float) MAX_WIDTH / longEdge;
                longEdge  = MAX_WIDTH;
                shortEdge = Math.round(shortEdge * scale);
            }
            int newW = ((newRawW >= newRawH) ? longEdge  : shortEdge) & ~1;
            int newH = ((newRawW >= newRawH) ? shortEdge : longEdge)  & ~1;
            if (newW <= 0 || newH <= 0) {
                ErrorReporter.get().warn(ErrorReporter.Source.DISPLAY_CHANGE,
                    "Invalid computed size " + newW + "x" + newH + ", skipping rebuild");
                return;
            }
            if (newW == capWidth && newH == capHeight) {
                pushSseEvent("{\"type\":\"orient\",\"w\":" + capWidth + ",\"h\":" + capHeight + "}");
                return;
            }

            TimeUnit.MILLISECONDS.sleep(150);
            if (!running) return;

            if (virtualDisplay != null) {
                try { virtualDisplay.setSurface(null); }
                catch (Exception ignored) {}
            }
            if (imageReader != null) {
                try {
                    imageReader.setOnImageAvailableListener(null, null);
                    imageReader.close();
                } catch (Exception e) {
                    ErrorReporter.get().warn(ErrorReporter.Source.SCREEN_CAPTURE,
                        "Error closing ImageReader: " + e.getMessage(), e);
                } finally {
                    imageReader = null;
                }
            }

            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                ErrorReporter.get().warn(ErrorReporter.Source.DISPLAY_CHANGE, "Display unavailable during rebuild");
                return;
            }
            DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            capDpi    = dm.densityDpi;
            capWidth  = newW;
            capHeight = newH;
            streamWidth.set(capWidth);
            streamHeight.set(capHeight);

            final int lW   = capWidth;
            final int lH   = capHeight;
            final int lDpi = capDpi;

            imageReader = ImageReader.newInstance(lW, lH, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                if (!running || rebuilding.get()) return;
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        byte[] jpeg = imageToJpeg(image, lW, lH);
                        image = null;
                        if (jpeg != null) latestFrame.set(jpeg);
                    }
                } catch (IllegalStateException ise) {
                    ErrorReporter.get().info(ErrorReporter.Source.SCREEN_CAPTURE,
                        "ImageReader closed during frame");
                } catch (Exception e) {
                    ErrorReporter.get().warn(ErrorReporter.Source.IMAGE_ENCODE,
                        "Frame acquire error: " + e.getMessage(), e);
                } finally {
                    if (image != null) image.close();
                }
            }, imageHandler);

            if (virtualDisplay != null) {
                try {
                    virtualDisplay.resize(lW, lH, lDpi);
                    virtualDisplay.setSurface(imageReader.getSurface());
                    pushSseEvent("{\"type\":\"orient\",\"w\":" + lW + ",\"h\":" + lH + "}");
                    ErrorReporter.get().info(ErrorReporter.Source.DISPLAY_CHANGE, "Rebuild done: " + lW + "x" + lH);
                } catch (SecurityException se) {
                    ErrorReporter.get().warn(ErrorReporter.Source.VIRTUAL_DISPLAY,
                        "MediaProjection token expired on display change — stopping capture");
                    stopCapture();
                    stopSelf();
                } catch (Exception e) {
                    ErrorReporter.get().error(ErrorReporter.Source.VIRTUAL_DISPLAY,
                        "VirtualDisplay.resize failed: " + e.getMessage(), e);
                    stopCapture();
                    stopSelf();
                }
            } else {
                ErrorReporter.get().warn(ErrorReporter.Source.VIRTUAL_DISPLAY,
                    "No VirtualDisplay available for rebuild — stopping capture");
                stopCapture();
                stopSelf();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ErrorReporter.get().warn(ErrorReporter.Source.DISPLAY_CHANGE, "safeRebuild interrupted");
        } catch (Exception e) {
            ErrorReporter.get().error(ErrorReporter.Source.DISPLAY_CHANGE,
                "safeRebuild unexpected error: " + e.getMessage(), e);
        } finally {
            rebuildPending.set(false);
            rebuilding.set(false);
        }
    }
    private void buildImageReader() {
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("Default display unavailable");
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            throw new IllegalStateException("Invalid display metrics: "
                + metrics.widthPixels + "x" + metrics.heightPixels);
        }
        lastKnownW = metrics.widthPixels;
        lastKnownH = metrics.heightPixels;
        buildImageReaderAtSize(lastKnownW, lastKnownH);
    }

    private void buildImageReaderAtSize(int rawW, int rawH) {
        if (rawW <= 0 || rawH <= 0) throw new IllegalArgumentException("Invalid size: " + rawW + "x" + rawH);
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("Default display unavailable");
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        capDpi = dm.densityDpi;

        int longEdge  = Math.max(rawW, rawH);
        int shortEdge = Math.min(rawW, rawH);
        int maxW = maxWidth.get();
        int maxH = maxHeight.get();
        if (longEdge > maxW) {
            float scale = (float) maxW / longEdge;
            longEdge  = maxW;
            shortEdge = Math.round(shortEdge * scale);
        }
        if (shortEdge > maxH) {
            float scale = (float) maxH / shortEdge;
            shortEdge = maxH;
            longEdge  = Math.round(longEdge * scale);
        }
        capWidth  = ((rawW >= rawH) ? longEdge  : shortEdge) & ~1;
        capHeight = ((rawW >= rawH) ? shortEdge : longEdge)  & ~1;
        if (capWidth <= 0 || capHeight <= 0) {
            throw new IllegalStateException("Computed invalid cap size: " + capWidth + "x" + capHeight);
        }

        streamWidth.set(capWidth);
        streamHeight.set(capHeight);

        final int rW = capWidth;
        final int rH = capHeight;
        imageReader = ImageReader.newInstance(rW, rH, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            if (!running || rebuilding.get()) return;
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    byte[] jpeg = imageToJpeg(image, rW, rH);
                    image = null;
                    if (jpeg != null) latestFrame.set(jpeg);
                }
            } catch (IllegalStateException ise) {
                ErrorReporter.get().info(ErrorReporter.Source.SCREEN_CAPTURE,
                    "ImageReader closed during frame");
            } catch (Exception e) {
                ErrorReporter.get().warn(ErrorReporter.Source.IMAGE_ENCODE,
                    "Frame acquire error: " + e.getMessage(), e);
            } finally {
                if (image != null) image.close();
            }
        }, imageHandler);

        ErrorReporter.get().info(ErrorReporter.Source.SCREEN_CAPTURE,
            "ImageReader: " + rW + "x" + rH + " dpi=" + capDpi);
    }
    private void buildVirtualDisplay() {
        if (mediaProjection == null) throw new IllegalStateException("MediaProjection is null");
        if (imageReader == null)     throw new IllegalStateException("ImageReader is null");
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenStream", capWidth, capHeight, capDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, imageHandler
        );
        if (virtualDisplay == null) throw new IllegalStateException("createVirtualDisplay returned null");
    }

    private byte[] imageToJpeg(Image image, int imgWidth, int imgHeight) {
        Bitmap bmp = null;
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                ErrorReporter.get().warn(ErrorReporter.Source.IMAGE_ENCODE, "Image has no planes");
                return null;
            }
            int pixelStride = planes[0].getPixelStride();
            int rowStride   = planes[0].getRowStride();
            if (pixelStride <= 0 || rowStride <= 0) {
                ErrorReporter.get().warn(ErrorReporter.Source.IMAGE_ENCODE,
                    "Invalid stride: pixel=" + pixelStride + " row=" + rowStride);
                return null;
            }
            ByteBuffer srcBuf = planes[0].getBuffer();
            byte[] pixels = new byte[srcBuf.remaining()];
            srcBuf.get(pixels);
            image.close();

            int rowPadding = rowStride - pixelStride * imgWidth;
            int bmpWidth   = imgWidth + (rowPadding > 0 ? rowPadding / pixelStride : 0);

            bmp = Bitmap.createBitmap(bmpWidth, imgHeight, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));

            if (rowPadding > 0) {
                Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, imgWidth, imgHeight);
                bmp.recycle();
                bmp = cropped;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(bmp.getByteCount() / 4);
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality.get(), baos)) {
                ErrorReporter.get().warn(ErrorReporter.Source.IMAGE_ENCODE, "Bitmap.compress returned false");
                return null;
            }
            return baos.toByteArray();

        } catch (OutOfMemoryError oom) {
            ErrorReporter.get().error(ErrorReporter.Source.IMAGE_ENCODE,
                "Out of memory encoding frame — reduce quality or resolution");
            return null;
        } catch (Exception e) {
            ErrorReporter.get().warn(ErrorReporter.Source.IMAGE_ENCODE,
                "JPEG encode failed: " + e.getMessage(), e);
            return null;
        } finally {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
    }
    private void stopCapture() {
        running = false;
        rebuildPending.set(false);

        try { displayManager.unregisterDisplayListener(displayListener); }
        catch (Exception ignored) {}

        if (frameDispatchThread != null) { frameDispatchThread.interrupt(); frameDispatchThread = null; }
        if (audioThread != null)         { audioThread.interrupt();         audioThread = null; }
        if (audioRecord != null) {
            try { audioRecord.stop();    } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }

        for (OutputStream os : videoClients) { try { os.close(); } catch (IOException ignored) {} }
        for (OutputStream os : sseClients)   { try { os.close(); } catch (IOException ignored) {} }
        for (OutputStream os : audioClients) { try { os.close(); } catch (IOException ignored) {} }
        videoClients.clear(); sseClients.clear(); audioClients.clear();

        if (virtualDisplay != null) {
            try { virtualDisplay.setSurface(null); virtualDisplay.release(); }
            catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.setOnImageAvailableListener(null, null); imageReader.close(); }
            catch (Exception ignored) {}
            imageReader = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        if (imageThread != null) {
            imageThread.quitSafely();
            imageThread = null;
            imageHandler = null;
        }
        latestFrame.set(null);

        // Close the listener so the next startCapture() rebuilds it fresh against the
        // current httpsEnabled value, instead of possibly reusing a socket opened under
        // a different protocol from a prior start/stop cycle.
        if (serverThread != null) { serverThread.interrupt(); serverThread = null; }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }

        releaseWakeLock();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            // SCREEN_BRIGHT_WAKE_LOCK is deprecated in favor of Window's
            // FLAG_KEEP_SCREEN_ON, but that only works while an Activity is in the
            // foreground. Streaming runs from this Service independent of MainActivity
            // (the whole point is the screen keeps mirroring after you leave the app),
            // so the deprecated screen-level wake lock is the only mechanism that
            // actually applies here.
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                "ScreenStream:keepAwake");
            lock.setReferenceCounted(false);
            lock.acquire();
            wakeLock = lock;
        } catch (Exception e) {
            ErrorReporter.get().warn(ErrorReporter.Source.SYSTEM, "Failed to acquire wake lock: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void startAudioCapture() {
        try {
            int sr    = audioSampleRate.get();
            int ch    = audioChannels.get();
            int enc   = audioEncoding.get();
            int chCfg = (ch == 1) ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;

            int minBuf = AudioRecord.getMinBufferSize(sr, chCfg, enc);
            if (minBuf <= 0) {
                ErrorReporter.get().warn(ErrorReporter.Source.AUDIO_CAPTURE,
                    "Requested encoding " + enc + " not supported at sr=" + sr + ", falling back to PCM_16BIT");
                enc = AudioFormat.ENCODING_PCM_16BIT;
                audioEncoding.set(enc);
                minBuf = AudioRecord.getMinBufferSize(sr, chCfg, enc);
            }
            if (minBuf <= 0) {
                ErrorReporter.get().error(ErrorReporter.Source.AUDIO_CAPTURE,
                    "AudioRecord.getMinBufferSize failed for sr=" + sr + " ch=" + ch);
                return;
            }
            if (mediaProjection == null) {
                ErrorReporter.get().error(ErrorReporter.Source.AUDIO_CAPTURE,
                    "Cannot start audio: MediaProjection is null");
                return;
            }

            final int bitsPerSample = (enc == AudioFormat.ENCODING_PCM_8BIT) ? 8
                : (enc == AudioFormat.ENCODING_PCM_FLOAT) ? 32 : 16;

            AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                    .build();

            audioRecord = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(enc)
                    .setSampleRate(sr)
                    .setChannelMask(chCfg)
                    .build())
                .setBufferSizeInBytes(minBuf * 16)
                .build();

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                ErrorReporter.get().error(ErrorReporter.Source.AUDIO_CAPTURE,
                    "AudioRecord init failed (state=" + audioRecord.getState() + ", sr=" + sr + ", enc=" + enc + ")");
                audioRecord.release();
                audioRecord = null;
                return;
            }

            try {
                audioRecord.startRecording();
            } catch (IllegalStateException e) {
                ErrorReporter.get().error(ErrorReporter.Source.AUDIO_CAPTURE,
                    "AudioRecord.startRecording failed: " + e.getMessage(), e);
                audioRecord.release();
                audioRecord = null;
                return;
            }

            final int finalReadUnit  = minBuf;
            final int finalSr        = sr;
            final int finalCh        = ch;
            final int finalEnc       = enc;
            final AtomicInteger consecutiveErrors = new AtomicInteger(0);

            currentAudioBits = bitsPerSample;

            audioThread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
                byte[] buf = new byte[finalReadUnit];
                while (running && !Thread.currentThread().isInterrupted()) {
                    if (!audioEnabled.get()) {
                        audioRecord.stop();
                        while (running && !audioEnabled.get() && !Thread.currentThread().isInterrupted()) {
                            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                        }
                        if (!running) break;
                        boolean started = false;
                        for (int retry = 0; retry < 3 && !started && running; retry++) {
                            try {
                                audioRecord.startRecording();
                                started = true;
                            } catch (Exception e) {
                                ErrorReporter.get().warn(ErrorReporter.Source.AUDIO_CAPTURE,
                                    "AudioRecord startRecording retry " + (retry + 1) + " failed: " + e.getMessage());
                                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                            }
                        }
                        if (!started) {
                            ErrorReporter.get().error(ErrorReporter.Source.AUDIO_CAPTURE,
                                "Failed to restart audio recording after 3 retries");
                            break;
                        }
                        continue;
                    }
                    if (audioSampleRate.get() != finalSr || audioChannels.get() != finalCh
                            || audioEncoding.get() != finalEnc) {
                        ErrorReporter.get().info(ErrorReporter.Source.AUDIO_CAPTURE, "Audio config changed");
                        if (imageHandler != null) imageHandler.post(this::restartAudioCapture);
                        break;
                    }
                    int read = audioRecord.read(buf, 0, finalReadUnit);
                    if (read < 0) {
                        String errName;
                        switch (read) {
                            case AudioRecord.ERROR_INVALID_OPERATION: errName = "ERROR_INVALID_OPERATION"; break;
                            case AudioRecord.ERROR_BAD_VALUE:         errName = "ERROR_BAD_VALUE";         break;
                            case AudioRecord.ERROR_DEAD_OBJECT:       errName = "ERROR_DEAD_OBJECT";       break;
                            default:                                  errName = "error " + read;
                        }
                        int errs = consecutiveErrors.incrementAndGet();
                        if (errs <= 3) {
                            ErrorReporter.get().warn(ErrorReporter.Source.AUDIO_CAPTURE,
                                "AudioRecord.read: " + errName + " (consecutive=" + errs + ")");
                        }
                        if (errs >= 10) {
                            ErrorReporter.get().error(ErrorReporter.Source.AUDIO_CAPTURE,
                                "AudioRecord.read failed 10 consecutive times, stopping audio");
                            break;
                        }
                        continue;
                    }
                    consecutiveErrors.set(0);
                    if (read > 0 && !audioClients.isEmpty()) {
                        for (OutputStream os : audioClients) {
                            try { os.write(buf, 0, read); os.flush(); }
                            catch (IOException e) {
                                audioClients.remove(os);
                                try { os.close(); } catch (IOException ignored) {}
                            }
                        }
                    }
                }
            }, "AudioCapture");
            audioThread.setDaemon(true);
            audioThread.start();
            ErrorReporter.get().info(ErrorReporter.Source.AUDIO_CAPTURE,
                "Audio: sr=" + sr + " ch=" + ch + " enc=" + enc + " bits=" + bitsPerSample);

        } catch (SecurityException se) {
            ErrorReporter.get().error(ErrorReporter.Source.AUDIO_CAPTURE,
                "RECORD_AUDIO permission denied: " + se.getMessage(), se);
        } catch (Exception e) {
            ErrorReporter.get().error(ErrorReporter.Source.AUDIO_CAPTURE,
                "Audio setup failed: " + e.getMessage(), e);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void restartAudioCapture() {
        if (audioThread != null) { audioThread.interrupt(); audioThread = null; }
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        startAudioCapture();
    }

    private void ensureHttpsKeyExists() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        // Clean up any key left behind under an old alias from a previous app version
        // (the alias has changed a few times while chasing KeyMint digest/padding
        // compatibility issues). Otherwise it just sits there unused forever, and -
        // more importantly - a stale entry is exactly what caused buildSslServerSocket()
        // to keep resolving to a broken key even after HTTPS_KEY_ALIAS pointed at a
        // working one, before FixedAliasKeyManager forced the alias explicitly.
        try {
            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (alias.startsWith("airscreen_https_key") && !alias.equals(HTTPS_KEY_ALIAS)) {
                    ks.deleteEntry(alias);
                }
            }
        } catch (GeneralSecurityException ignored) {}

        if (ks.containsAlias(HTTPS_KEY_ALIAS)) return;

        Calendar notBefore = Calendar.getInstance();
        Calendar notAfter  = Calendar.getInstance();
        notAfter.add(Calendar.YEAR, 30);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        kpg.initialize(new KeyGenParameterSpec.Builder(HTTPS_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            // Confirmed via logcat on a real device (OnePlus 9 Pro, Android 16):
            // Conscrypt doesn't ask Keymint to hash-and-sign - it computes the TLS
            // transcript hash itself in software and asks the key for a raw signature
            // over those already-hashed bytes ("NONEwithRSA"). That requires DIGEST_NONE
            // to be in the declared set; without it Keymint legitimately refuses the
            // operation with INCOMPATIBLE_DIGEST, every time, for every cipher/protocol
            // combination, which is exactly what was observed. SHA256 is kept alongside
            // it for any path that does ask for a full hash-and-sign operation.
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
            // PSS was included here too, and Android used it to self-sign the generated
            // certificate (RSASSA-PSS AlgorithmIdentifier, confirmed by decoding the
            // actual cert presented over the wire). The client then hard-rejected it
            // with a fatal bad_certificate alert - PKCS1 alone forces the far more
            // universally-parsed sha256WithRSAEncryption self-signature instead.
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            // Still failing with DIGEST_NONE declared: logcat showed Conscrypt actually
            // performs its raw-sign fallback through a Cipher ("RSA/ECB/NoPadding"), not
            // a Signature instance, which needs ENCRYPTION_PADDING_NONE - PKCS1 alone
            // isn't enough even with the right digest declared.
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,
                KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .setCertificateSubject(new X500Principal("CN=ScreenStream Self-Signed"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(notBefore.getTime())
            .setCertificateNotAfter(notAfter.getTime())
            .build());
        kpg.generateKeyPair();
    }

    private ServerSocket buildSslServerSocket(int port) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, null);

        // The default X509ExtendedKeyManager picks whichever alias it finds suitable
        // on its own, not necessarily HTTPS_KEY_ALIAS - confirmed live (via logcat on
        // a real device) that it kept resolving to a stale key left behind by an
        // earlier app version even after a newer, working key existed under a new
        // alias. Force it explicitly instead of trusting auto-selection.
        KeyManager[] managers = kmf.getKeyManagers();
        for (int i = 0; i < managers.length; i++) {
            if (managers[i] instanceof X509ExtendedKeyManager) {
                managers[i] = new FixedAliasKeyManager((X509ExtendedKeyManager) managers[i], HTTPS_KEY_ALIAS);
            }
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(managers, null, null);
        return ((SSLServerSocketFactory) ctx.getServerSocketFactory()).createServerSocket(port);
    }

    /** Forces server-side alias selection to a specific alias instead of relying on
     *  the delegate's own (not always predictable) choice among possibly several
     *  candidates in the KeyStore. */
    private static class FixedAliasKeyManager extends X509ExtendedKeyManager {
        private final X509ExtendedKeyManager delegate;
        private final String alias;

        FixedAliasKeyManager(X509ExtendedKeyManager delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return alias;
        }
        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return alias;
        }
        @Override public X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }
        @Override public PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }
        @Override public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            return delegate.chooseClientAlias(keyTypes, issuers, socket);
        }
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }
    }

    private void startHttpServer(int port) {
        serverThread = new Thread(() -> {
            try {
                if (httpsEnabled.get()) {
                    try {
                        ensureHttpsKeyExists();
                        serverSocket = buildSslServerSocket(port);
                    } catch (Exception e) {
                        // A key generated by an older build (or a partial/corrupt entry
                        // from a prior failed attempt) can leave AndroidKeyStore unable to
                        // hand back a usable TLS credential. Drop it and generate a fresh
                        // one once instead of getting stuck failing forever: without this,
                        // the thread used to die here with nothing listening on the port at
                        // all, which shows up to a client as the connection being reset.
                        ErrorReporter.get().warn(ErrorReporter.Source.HTTP_SERVER,
                            "HTTPS key/socket setup failed, regenerating: " + e.getMessage());
                        try {
                            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                            ks.load(null);
                            ks.deleteEntry(HTTPS_KEY_ALIAS);
                        } catch (Exception ignored) {}
                        ensureHttpsKeyExists();
                        serverSocket = buildSslServerSocket(port);
                    }
                } else {
                    serverSocket = new ServerSocket(port);
                }
                // Bind to a local reference: stopCapture() runs on a different thread and
                // closes and nulls the serverSocket field once the client stops streaming.
                // Reading the field itself from inside the accept loop below (instead of a
                // stable local copy) raced with that, producing a NullPointerException on
                // serverSocket.isClosed() when the field went null between the accept()
                // failing and the isClosed() check running.
                ServerSocket socket = serverSocket;
                socket.setReuseAddress(true);
                ErrorReporter.get().info(ErrorReporter.Source.HTTP_SERVER,
                    (httpsEnabled.get() ? "Listening (HTTPS) on port " : "Listening on port ") + port);
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket client = socket.accept();
                        new Thread(() -> handleClient(client), "HttpClient").start();
                    } catch (IOException e) {
                        if (!socket.isClosed()) {
                            ErrorReporter.get().warn(ErrorReporter.Source.HTTP_SERVER,
                                "Accept error: " + e.getMessage());
                        } else break;
                    }
                }
            } catch (BindException be) {
                ErrorReporter.get().error(ErrorReporter.Source.HTTP_SERVER,
                    bindFailureMessage(port, be.getMessage()), be);
            } catch (GeneralSecurityException gse) {
                ErrorReporter.get().error(ErrorReporter.Source.HTTP_SERVER,
                    "Failed to set up HTTPS certificate: " + gse.getMessage(), gse);
            } catch (IOException e) {
                ErrorReporter.get().error(ErrorReporter.Source.HTTP_SERVER,
                    "Server socket error: " + e.getMessage(), e);
            } catch (Exception e) {
                // Catches anything the three cases above don't, e.g. an unchecked
                // ProviderException from AndroidKeyStore/Keymaster. Without this the
                // thread used to die here with nothing bound to the port, which a client
                // sees as the connection being reset rather than a clear in-app error.
                ErrorReporter.get().error(ErrorReporter.Source.HTTP_SERVER,
                    "Unexpected HTTP server failure: " + e.getMessage(), e);
            }
        }, "HttpServer");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    static String bindFailureMessage(int port, String exceptionMessage) {
        String msg = exceptionMessage != null ? exceptionMessage : "";
        String lower = msg.toLowerCase(Locale.ROOT);
        if (msg.contains("EACCES") || lower.contains("permission denied")) {
            return "Port " + port + " needs root — Android only allows apps to bind ports "
                + "1024 and above without root. Try a port ≥ 1024.";
        }
        if (msg.contains("EADDRINUSE") || lower.contains("address already in use")) {
            return "Port " + port + " is already in use by another app.";
        }
        return "Failed to bind port " + port + ": " + msg;
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            byte[] buf = new byte[8192];
            int n = socket.getInputStream().read(buf);
            if (n <= 0) { socket.close(); return; }
            String request = new String(buf, 0, n, StandardCharsets.UTF_8);
            String[] lines = request.split("\r\n");
            String[] parts = lines[0].split(" ");
            if (parts.length < 2) { sendHttpError(socket, 400, "Bad Request"); return; }
            String rawPath = parts[1];
            String path    = rawPath;
            String query   = "";
            int qIdx = rawPath.indexOf('?');
            if (qIdx >= 0) {
                path  = rawPath.substring(0, qIdx);
                query = rawPath.substring(qIdx + 1);
            }
            Map<String, String> headers = parseHeaders(lines);
            socket.setSoTimeout(0);

            if (!isAuthorized(query, headers)) {
                sendAuthChallenge(socket);
                return;
            }

            switch (path) {
                case "/stream": serveMjpegStream(socket); break;
                case "/audio":  serveAudioStream(socket); break;
                case "/events": serveSseStream(socket);   break;
                default:        serveHtml(socket);        break;
            }
        } catch (java.net.SocketTimeoutException e) {
            ErrorReporter.get().info(ErrorReporter.Source.NETWORK,
                "Client request timed out: " + socket.getRemoteSocketAddress());
            try { socket.close(); } catch (IOException ignored) {}
        } catch (Exception e) {
            ErrorReporter.get().warn(ErrorReporter.Source.NETWORK, "Client handler error: " + e.getMessage());
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    static Map<String, String> parseHeaders(String[] requestLines) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < requestLines.length; i++) {
            String line = requestLines[i];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    boolean isAuthorized(String query, Map<String, String> headers) {
        switch (authMode.get()) {
            case NONE:
                return true;
            case PIN: {
                String pin = requiredPin.get();
                return pin != null && !pin.isEmpty() && constantTimeEquals(extractQueryParam(query, "pin"), pin);
            }
            case BASIC: {
                if (basicUser.get().isEmpty() && basicPass.get().isEmpty()) return false;
                String authz = headers.get("authorization");
                if (authz == null || !authz.regionMatches(true, 0, "Basic ", 0, 6)) return false;
                try {
                    byte[] decoded = Base64.decode(authz.substring(6), Base64.DEFAULT);
                    String creds = new String(decoded, StandardCharsets.UTF_8);
                    int sep = creds.indexOf(':');
                    if (sep < 0) return false;
                    boolean userOk = constantTimeEquals(creds.substring(0, sep), basicUser.get());
                    boolean passOk = constantTimeEquals(creds.substring(sep + 1), basicPass.get());
                    return userOk & passOk;
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }
            default:
                return true;
        }
    }

    private void sendAuthChallenge(Socket socket) {
        try {
            if (authMode.get() == AuthMode.BASIC) {
                String body = "<h1>401 Unauthorized</h1>";
                PrintStream ps = new PrintStream(socket.getOutputStream());
                ps.print("HTTP/1.1 401 Unauthorized\r\n");
                ps.print("WWW-Authenticate: Basic realm=\"ScreenStream\"\r\n");
                ps.print("Content-Type: text/html\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n");
                ps.print(body);
                ps.flush();
                socket.close();
            } else {
                sendHttpError(socket, 403, "Forbidden");
            }
        } catch (IOException ignored) {
            try { socket.close(); } catch (IOException ignore) {}
        }
    }

    static String extractQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(key)) return eq >= 0 ? pair.substring(eq + 1) : "";
        }
        return null;
    }

    static boolean constantTimeEquals(String a, String b) {
        byte[] ab = (a != null ? a : "").getBytes(StandardCharsets.UTF_8);
        byte[] bb = (b != null ? b : "").getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    private void sendHttpError(Socket socket, int code, String text) {
        try {
            String body = "<h1>" + code + " " + text + "</h1>";
            PrintStream ps = new PrintStream(socket.getOutputStream());
            ps.print("HTTP/1.1 " + code + " " + text + "\r\n");
            ps.print("Content-Type: text/html\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n");
            ps.print(body);
            ps.flush();
            socket.close();
        } catch (IOException ignored) {}
    }

    private void serveMjpegStream(Socket socket) {
        try {
            if (!running) { sendHttpError(socket, 503, "Stream not active"); return; }
            OutputStream os = socket.getOutputStream();
            PrintStream  ps = new PrintStream(os);
            ps.print("HTTP/1.1 200 OK\r\n");
            ps.print("Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n");
            ps.print("Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n");
            ps.flush();
            videoClients.add(os);
            ErrorReporter.get().info(ErrorReporter.Source.NETWORK,
                "Video client connected (total=" + videoClients.size() + ")");
            drainUntilClosed(socket);
        } catch (Exception e) {
            ErrorReporter.get().warn(ErrorReporter.Source.NETWORK, "MJPEG setup error: " + e.getMessage(), e);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void serveAudioStream(Socket socket) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                sendHttpError(socket, 501, "Audio requires Android 10+");
                return;
            }
            int sr   = audioSampleRate.get();
            int ch   = audioChannels.get();
            int bits = currentAudioBits;
            BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream(), 65536);
            PrintStream ps = new PrintStream(bos);
            ps.print("HTTP/1.1 200 OK\r\n");
            ps.print("Content-Type: audio/raw\r\n");
            ps.print("X-Audio-SampleRate: " + sr + "\r\n");
            ps.print("X-Audio-Channels: "   + ch + "\r\n");
            ps.print("X-Audio-Bits: "       + bits + "\r\n");
            ps.print("Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n");
            ps.flush();
            audioClients.add(bos);
            ErrorReporter.get().info(ErrorReporter.Source.NETWORK,
                "Audio client connected sr=" + sr + " ch=" + ch + " (total=" + audioClients.size() + ")");
            drainUntilClosed(socket);
        } catch (Exception e) {
            ErrorReporter.get().warn(ErrorReporter.Source.NETWORK,
                "Audio stream setup error: " + e.getMessage(), e);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void serveSseStream(Socket socket) {
        try {
            OutputStream os = socket.getOutputStream();
            PrintStream  ps = new PrintStream(os);
            ps.print("HTTP/1.1 200 OK\r\n");
            ps.print("Content-Type: text/event-stream\r\nCache-Control: no-cache\r\n");
            ps.print("Access-Control-Allow-Origin: *\r\nConnection: keep-alive\r\n\r\n");
            int sid = sessionId.get(), w = streamWidth.get(), h = streamHeight.get();
            ps.print("data:{\"type\":\"session\",\"id\":" + sid + ",\"w\":" + w + ",\"h\":" + h + "}\n\n");
            ps.flush();
            sseClients.add(os);
            for (ErrorReporter.AppError err : ErrorReporter.get().getRecentErrors()) {
                try { os.write(("data:{\"type\":\"error\",\"error\":" + err.toJson() + "}\n\n").getBytes(StandardCharsets.UTF_8)); }
                catch (Exception ignored) {}
            }
            try { os.flush(); } catch (Exception ignored) {}
            drainUntilClosed(socket);        } catch (Exception e) {
            ErrorReporter.get().warn(ErrorReporter.Source.NETWORK, "SSE setup error: " + e.getMessage(), e);
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void drainUntilClosed(Socket socket) {
        try {
            byte[] buf = new byte[256];
            while (socket.getInputStream().read(buf) != -1) { }
        } catch (Exception ignored) {}
    }

    private void pushSseEvent(String json) {
        if (sseClients.isEmpty()) return;
        byte[] msg = ("data:" + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (OutputStream os : sseClients) {
            try { os.write(msg); os.flush(); }
            catch (IOException e) {
                sseClients.remove(os);
                try { os.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void startFrameDispatch() {
        frameDispatchThread = new Thread(() -> {
            byte[] lastSent = null;
            int    errors   = 0;
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] frame = latestFrame.get();
                    if (frame != null && frame != lastSent && !videoClients.isEmpty()) {
                        lastSent = frame;
                        byte[] header = ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                            + frame.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                        byte[] footer = "\r\n".getBytes(StandardCharsets.UTF_8);
                        for (OutputStream os : videoClients) {
                            try { os.write(header); os.write(frame); os.write(footer); os.flush(); errors = 0; }
                            catch (IOException e) {
                                ErrorReporter.get().info(ErrorReporter.Source.NETWORK, "Video client disconnected");
                                videoClients.remove(os);
                                try { os.close(); } catch (IOException ignored) {}
                            }
                        }
                    }
                    TimeUnit.MILLISECONDS.sleep(Math.max(1, 1000L / Math.max(1, targetFps.get())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (++errors <= 3) {
                        ErrorReporter.get().warn(ErrorReporter.Source.SCREEN_CAPTURE,
                            "Frame dispatch error: " + e.getMessage(), e);
                    }
                    if (errors >= 20) {
                        ErrorReporter.get().error(ErrorReporter.Source.SCREEN_CAPTURE,
                            "Frame dispatch failing repeatedly, stopping");
                        break;
                    }
                }
            }
        }, "FrameDispatch");
        frameDispatchThread.setDaemon(true);
        frameDispatchThread.start();
    }

    private void serveHtml(Socket socket) throws IOException {
        String pinForClient = (authMode.get() == AuthMode.PIN) ? requiredPin.get() : "";
        byte[] body = buildViewerHtml(pinForClient).getBytes(StandardCharsets.UTF_8);
        PrintStream ps = new PrintStream(socket.getOutputStream());
        ps.print("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n");
        ps.print("Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n");
        ps.flush();
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
        socket.close();
    }

    private static String jsStringLiteral(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') sb.append('\\');
            sb.append(c);
        }
        return sb.append('\'').toString();
    }

    private String buildViewerHtml(String pin) {
        return "<!DOCTYPE html><html lang='en'><head>"
+ "<meta charset='utf-8'>"
+ "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
+ "<title>Miroir</title>"
+ "<style>"
+ ":root{--bg:#0a0a0f;--sur:#13131a;--bdr:#1e1e2e;--acc:#4f8ef7;--ac2:#7c5cbf;--txt:#e2e2f0;--mut:#555570;--grn:#22c55e;--red:#ef4444;--yel:#f59e0b;}"
+ "*{margin:0;padding:0;box-sizing:border-box;}"
+ "html,body{height:100%;background:var(--bg);color:var(--txt);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;overflow:hidden;}"
+ "#app{display:flex;flex-direction:column;height:100vh;}"
+ "#topbar{display:flex;align-items:center;padding:8px 14px;background:var(--sur);border-bottom:1px solid var(--bdr);flex-shrink:0;gap:10px;}"
+ "#topbar h1{font-size:.95rem;font-weight:700;letter-spacing:.06em;background:linear-gradient(90deg,var(--acc),var(--ac2));-webkit-background-clip:text;-webkit-text-fill-color:transparent;white-space:nowrap;}"
+ "#badge{display:flex;align-items:center;gap:5px;font-size:.72rem;color:var(--mut);}"
+ "#dot{width:7px;height:7px;border-radius:50%;background:var(--grn);box-shadow:0 0 5px var(--grn);animation:pulse 2s infinite;}"
+ "@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}"
+ "#controls{display:flex;align-items:center;gap:6px;margin-left:auto;}"
+ ".cb{background:var(--sur);border:1px solid var(--bdr);color:var(--txt);padding:4px 10px;border-radius:5px;cursor:pointer;font-size:.78rem;white-space:nowrap;transition:border-color .15s,color .15s;}"
+ ".cb:hover{border-color:var(--acc);color:var(--acc);}.cb.on{background:var(--acc);border-color:var(--acc);color:#fff;}"
+ "#obadge{font-size:.68rem;color:var(--mut);padding:3px 7px;border:1px solid var(--bdr);border-radius:4px;white-space:nowrap;}"
+ "#stage{flex:1;display:flex;align-items:center;justify-content:center;position:relative;overflow:hidden;background:#000;}"
+ "#si{display:block;max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain;}"
+ "#si:-webkit-full-screen{width:100vw!important;height:100vh!important;max-width:100vw;max-height:100vh;object-fit:contain;background:#000;}"
+ "#si:fullscreen{width:100vw!important;height:100vh!important;max-width:100vw;max-height:100vh;object-fit:contain;background:#000;}"
+ "#overlay{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;background:rgba(0,0,0,.75);}"
+ "#overlay span{font-size:1rem;color:var(--mut);text-align:center;padding:0 20px;}"
+ "#spinner{width:36px;height:36px;border:3px solid var(--bdr);border-top-color:var(--acc);border-radius:50%;animation:spin .8s linear infinite;}"
+ "@keyframes spin{to{transform:rotate(360deg)}}"
+ "#err-toast{position:absolute;bottom:50px;left:50%;transform:translateX(-50%);background:#1a0a0a;border:1px solid var(--red);color:#fca5a5;padding:10px 18px;border-radius:8px;font-size:.82rem;max-width:420px;text-align:center;display:none;z-index:200;box-shadow:0 4px 20px rgba(0,0,0,.6);}"
+ "#err-toast.warn{border-color:var(--yel);color:#fcd34d;background:#1a1500;}"
+ "#err-panel{position:absolute;top:0;right:0;bottom:0;width:340px;background:#0d0d14;border-left:1px solid var(--bdr);overflow-y:auto;z-index:150;display:none;flex-direction:column;}"
+ "#err-panel.open{display:flex;}"
+ "#err-panel-hdr{display:flex;align-items:center;justify-content:space-between;padding:10px 14px;border-bottom:1px solid var(--bdr);flex-shrink:0;}"
+ "#err-panel-hdr h3{font-size:.85rem;color:var(--txt);}"
+ "#err-close{background:none;border:none;color:var(--mut);cursor:pointer;font-size:1rem;padding:2px 6px;}"
+ "#err-list{flex:1;overflow-y:auto;padding:8px;}"
+ ".err-item{margin-bottom:8px;padding:8px 10px;border-radius:6px;background:#13131a;border-left:3px solid var(--bdr);font-size:.75rem;}"
+ ".err-item.INFO{border-color:var(--acc);}.err-item.WARNING{border-color:var(--yel);}.err-item.ERROR{border-color:var(--red);}.err-item.FATAL{border-color:var(--red);background:#1a0808;}"
+ ".err-src{font-weight:700;color:var(--txt);margin-bottom:3px;}.err-msg{color:#aaa;line-height:1.4;}.err-time{color:var(--mut);font-size:.68rem;margin-top:4px;}"
+ "#bottombar{padding:6px 14px;background:var(--sur);border-top:1px solid var(--bdr);display:flex;align-items:center;gap:10px;flex-shrink:0;font-size:.7rem;color:var(--mut);}"
+ "#fps{color:var(--acc);font-weight:700;min-width:40px;}"
+ "#lat{color:var(--yel);font-weight:700;min-width:50px;}"
+ ".spark{display:inline-block;vertical-align:middle;width:48px;height:1em;margin-left:4px;}"
+ "#abar-w{flex:1;max-width:140px;height:3px;background:var(--bdr);border-radius:2px;overflow:hidden;}"
+ "#abar{height:100%;width:0%;background:linear-gradient(90deg,var(--acc),var(--ac2));transition:width .07s;}"
+ "#res{margin-left:auto;}"
+ "#err-badge{cursor:pointer;padding:2px 7px;border-radius:4px;background:transparent;font-size:.7rem;display:none;}"
+ "#err-badge.has-errors{display:inline;background:#2a0a0a;border:1px solid var(--red);color:#fca5a5;}"
+ "#err-badge.has-warnings{background:#1a1200;border:1px solid var(--yel);color:#fcd34d;}"
+ "#apanel{display:none;position:absolute;top:46px;right:10px;background:var(--sur);border:1px solid var(--bdr);border-radius:10px;padding:14px;z-index:100;min-width:220px;box-shadow:0 8px 32px rgba(0,0,0,.5);}"
+ "#apanel.open{display:block;}"
+ "#apanel h3{font-size:.8rem;color:var(--mut);letter-spacing:.08em;margin-bottom:10px;}"
+ ".ar{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;font-size:.8rem;}"
+ ".ar label{color:var(--txt);}"
+ ".asel{background:var(--bg);border:1px solid var(--bdr);color:var(--txt);padding:3px 6px;border-radius:5px;font-size:.78rem;}"
+ "</style></head><body>"
+ "<div id='app'>"
+ "<div id='topbar'>"
+ "<h1>Miroir</h1>"
+ "<div id='badge'><div id='dot'></div><span id='stxt'>Connecting...</span></div>"
+ "<div id='controls'>"
+ "<span id='obadge'>--</span>"
+ "<button class='cb on' id='btn-a' onclick='toggleAudio()'>Audio ON</button>"
+ "<button class='cb' id='btn-as' onclick='togglePanel()'>Settings</button>"
+ "<button class='cb' onclick='toggleFs()'>Fullscreen</button>"
+ "</div></div>"
+ "<div id='apanel'>"
+ "<h3>AUDIO SETTINGS</h3>"
+ "<div class='ar'><label>Latency</label>"
+ "<select class='asel' id='sel-lat'>"
+ "<option value='0.08'>Low (80ms)</option>"
+ "<option value='0.15' selected>Normal (150ms)</option>"
+ "<option value='0.3'>Stable (300ms)</option>"
+ "<option value='0.6'>Buffered (600ms)</option>"
+ "</select></div>"
+ "</div>"
+ "<div id='err-panel'>"
+ "<div id='err-panel-hdr'><h3>Diagnostics</h3><button id='err-close' onclick='closeErrPanel()'>x</button></div>"
+ "<div id='err-list'><p style='color:var(--mut);font-size:.78rem;padding:10px;'>No events yet.</p></div>"
+ "</div>"
+ "<div id='stage'>"
+ "<img id='si' alt='stream' onload='onLoad()' onerror='onErr()' onabort='onAbort()'/>"
+ "<div id='overlay'><div id='spinner'></div><span id='ovtxt'>Connecting...</span></div>"
+ "</div>"
+ "<div id='err-toast'></div>"
+ "<div id='bottombar'>"
+ "<span id='fps'>- fps</span><canvas class='spark' id='fpsSpark'></canvas>"
+ "<span id='lat'> - ms</span><canvas class='spark' id='latSpark'></canvas>"
+ "<div id='abar-w'><div id='abar'></div></div>"
+ "<span id='res'>-</span>"
+ "<span id='err-badge' onclick='openErrPanel()'></span>"
+ "</div></div>"
+ "<script>"
+ "var PIN=" + jsStringLiteral(pin) + ";"
+ "var si=document.getElementById('si');"
+ "var overlay=document.getElementById('overlay');"
+ "var ovtxt=document.getElementById('ovtxt');"
+ "var stxt=document.getElementById('stxt');"
+ "var fpsCtr=document.getElementById('fps');"
+ "var latCtr=document.getElementById('lat');"
+ "var resCtr=document.getElementById('res');"
+ "var fpsSpark=document.getElementById('fpsSpark');"
+ "var latSpark=document.getElementById('latSpark');"
+ "var obadge=document.getElementById('obadge');"
+ "var abar=document.getElementById('abar');"
+ "var btnA=document.getElementById('btn-a');"
+ "var errBadge=document.getElementById('err-badge');"
+ "var errToast=document.getElementById('err-toast');"
+ "var errCount=0,warnCount=0,toastTimer=null;"
+ "function showToast(msg,isWarn){"
+ "  errToast.textContent=msg;errToast.className=isWarn?'warn':'';"
+ "  errToast.style.display='block';"
+ "  if(toastTimer)clearTimeout(toastTimer);"
+ "  toastTimer=setTimeout(function(){errToast.style.display='none';},6000);"
+ "}"
+ "function addErrItem(err){"
+ "  var el=document.createElement('div');"
+ "  el.className='err-item '+(err.level||'INFO');"
+ "  var t=new Date(err.ts||Date.now()).toLocaleTimeString();"
+ "  el.innerHTML='<div class=\"err-src\">['+( err.source||'?')+'] '+(err.level||'')+'</div>'"
+ "    +'<div class=\"err-msg\">'+(err.msg||'')+'</div>'"
+ "    +'<div class=\"err-time\">'+t+'</div>';"
+ "  var list=document.getElementById('err-list');"
+ "  if(list.children.length===1&&list.children[0].tagName==='P')list.innerHTML='';"
+ "  list.insertBefore(el,list.firstChild);"
+ "  while(list.children.length>50)list.removeChild(list.lastChild);"
+ "}"
+ "function handleErrEvent(err){"
+ "  addErrItem(err);"
+ "  var lvl=err.level||'INFO';"
+ "  if(lvl==='ERROR'||lvl==='FATAL'){"
+ "    errCount++;showToast('['+err.source+'] '+err.msg,false);"
+ "    errBadge.textContent=errCount+' error'+(errCount>1?'s':'');"
+ "    errBadge.className='has-errors';"
+ "  } else if(lvl==='WARNING'){"
+ "    warnCount++;"
+ "    if(errCount===0){errBadge.textContent=warnCount+' warning'+(warnCount>1?'s':'');errBadge.className='has-warnings';}"
+ "    showToast('['+err.source+'] '+err.msg,true);"
+ "  }"
+ "}"
+ "function openErrPanel(){document.getElementById('err-panel').classList.add('open');}"
+ "function closeErrPanel(){document.getElementById('err-panel').classList.remove('open');}"
+ "var vRetry=0,vTimer=null,knownSession=0,fc=0,ft=Date.now(),lastFrameTime=0,lastFrameHash='';"
+ "function connectVideo(){var now=Date.now();si.dataset.reqTs=String(now);si.src='';si.src='/stream?t='+now+'&r='+Math.random()+(PIN?('&pin='+PIN):'');}"
+ "function onLoad(){"
+ "  var now=Date.now();lastFrameTime=now;"
+ "  var reqTs=parseInt(si.dataset.reqTs||'0',10);"
+ "  if(reqTs>0){var lat=now-reqTs;latCtr.textContent=' - '+lat+' ms';drawSpark(latSpark,lat,100);}"
+ "  else{latCtr.textContent=' - N/A';}"
+ "  vRetry=0;if(vTimer){clearTimeout(vTimer);vTimer=null;}"
+ "  fc++;var n=now;if(n-ft>=1000){fpsCtr.textContent=fc+' fps';drawSpark(fpsSpark,fc,60);fc=0;ft=n;}"
+ "  overlay.style.display='none';stxt.textContent='Live';"
+ "}"
+ "function onErr(){"
+ "  var delay=Math.min(8000,1000*Math.pow(2,vRetry));vRetry++;"
+ "  overlay.style.display='flex';stxt.textContent='Reconnecting...';"
+ "  ovtxt.textContent='Retry in '+(delay/1000|0)+'s';"
+ "  vTimer=setTimeout(function(){ovtxt.textContent='Connecting...';connectVideo();},delay);"
+ "}"
+ "function onAbort(){"
+ "  if(!vTimer){"
+ "    vRetry=0;"
+ "    ovtxt.textContent='Connection lost...';"
+ "    overlay.style.display='flex';"
+ "    connectVideo();"
+ "  }"
+ "}"
+ "function checkFrameStall(){"
+ "  if(!si || si.readyState < 2) return;"
+ "  try{"
+ "    var canvas=document.createElement('canvas');"
+ "    canvas.width=16;canvas.height=16;"
+ "    var ctx=canvas.getContext('2d');"
+ "    ctx.drawImage(si,0,0,16,16);"
+ "    var data=ctx.getImageData(0,0,16,16).data;"
+ "    var hash='';"
+ "    for(var i=0;i<data.length;i+=4){hash+=data[i]+','+data[i+1]+','+data[i+2]+';';}"
+ "    if(lastFrameHash && hash===lastFrameHash){"
+ "      if(Date.now()-lastFrameTime>5000 && !vTimer){"
+ "        console.log('Frame stall detected, reconnecting...');"
+ "        onAbort();"
+ "        return;"
+ "      }"
+ "    }else{"
+ "      lastFrameHash=hash;"
+ "      lastFrameTime=Date.now();"
+ "    }"
+ "  }catch(e){}"
+ "}"
+ "setInterval(checkFrameStall,1000);"
+ "connectVideo();"
+ "function drawSpark(c,v,m){if(!c)return;var x=c.getContext('2d');if(!x)return;var lastV=parseFloat(c.dataset.lastV||'0');if(Math.abs(v-lastV)<m*0.02)return;c.dataset.lastV=String(v);if(!c.dataset.hist)c.dataset.hist=JSON.stringify([]);var h=JSON.parse(c.dataset.hist);h.push(v);if(h.length>50)h.shift();c.dataset.hist=JSON.stringify(h);x.clearRect(0,0,c.width,c.height);x.strokeStyle='#4f8ef7';x.lineWidth=1;x.beginPath();for(var i=0;i<h.length;i++){var px=(i/h.length)*c.width;var py=c.height-(h[i]/m)*c.height;if(i===0)x.moveTo(px,py);else x.lineTo(px,py);}x.stroke();}"
+ "(function sse(){"
+ "  var es=new EventSource('/events'+(PIN?('?pin='+PIN):''));"
+ "  es.onmessage=function(e){"
+ "    try{"
+ "      var d=JSON.parse(e.data);"
+ "      if(d.type==='error'&&d.error){handleErrEvent(d.error);return;}"
+ "      if(d.w&&d.h){"
+ "        si.setAttribute('width',d.w);si.setAttribute('height',d.h);"
+ "        si.style.aspectRatio=d.w+'/'+d.h;"
+ "        obadge.textContent=(d.w>=d.h?'Landscape':'Portrait')+' '+d.w+'x'+d.h;"
+ "        resCtr.textContent=d.w+'x'+d.h;"
+ "      }"
+ "      if(d.type==='session'){"
+ "        var sid=d.id;"
+ "        if(sid!==knownSession&&knownSession!==0){"
+ "          knownSession=sid;"
+ "          if(vTimer){clearTimeout(vTimer);vTimer=null;}"
+ "          vRetry=0;ovtxt.textContent='Stream restarted...';overlay.style.display='flex';"
+ "          setTimeout(connectVideo,300);"
+ "          if(audioWanted){_stopAudioInternal();aRetry=0;setTimeout(startAudio,500);}"
+ "        } else {knownSession=sid;}"
+ "      }"
+ "    }catch(ex){console.warn('SSE parse error:',ex);}"
+ "  };"
+ "  es.onerror=function(){es.close();setTimeout(sse,3000);};"
+ "})();"
+ "function toggleFs(){"
+ "  var inFs=document.fullscreenElement||document.webkitFullscreenElement;"
+ "  if(!inFs){var r=si.requestFullscreen||si.webkitRequestFullscreen;if(r)r.call(si).catch(function(e){showToast('Fullscreen error: '+e.message,true);});}"
+ "  else{var x=document.exitFullscreen||document.webkitExitFullscreen;if(x)x.call(document);}"
+ "}"
+ "function togglePanel(){document.getElementById('apanel').classList.toggle('open');}"
+ "document.addEventListener('click',function(e){"
+ "  var p=document.getElementById('apanel'),b=document.getElementById('btn-as');"
+ "  if(p.classList.contains('open')&&!p.contains(e.target)&&e.target!==b)p.classList.remove('open');"
+ "});"
+ "var actx=null,aActive=false,aReader=null,nextT=0;"
+ "var SR=44100,CH=2,MAX_AHEAD=1.0;"
+ "var CHUNK=1024;"
+ "var aRetry=0,audioWanted=true,aTimer=null;"
+ "function getLat(){return parseFloat(document.getElementById('sel-lat').value);}"
+ "async function startAudio(){"
+ "  if(!audioWanted || aActive)return;"
+ "  try{"
+ "    if(actx){try{await actx.suspend();}catch(e){}actx=null;}"
+ "    actx=new(window.AudioContext||window.webkitAudioContext)();"
+ "    await actx.resume();"
+ "    var analyser=actx.createAnalyser();analyser.fftSize=128;"
+ "    analyser.connect(actx.destination);"
+ "    var arr=new Uint8Array(analyser.frequencyBinCount);"
+ "    (function bar(){if(!aActive)return;analyser.getByteFrequencyData(arr);var s=0;for(var i=0;i<arr.length;i++)s+=arr[i];abar.style.width=Math.min(100,s/arr.length*2.5)+'%';requestAnimationFrame(bar);})();"
+ "    var resp=await fetch('/audio'+(PIN?('?pin='+PIN):''));"
+ "    if(!resp.ok)throw new Error('HTTP '+resp.status);"
+ "    var hSR=resp.headers.get('X-Audio-SampleRate'),hCH=resp.headers.get('X-Audio-Channels'),hBits=resp.headers.get('X-Audio-Bits');"
+ "    SR=hSR?parseInt(hSR):44100;CH=hCH?parseInt(hCH):2;"
+ "    var BITS=hBits?parseInt(hBits):16;"
+ "    var BPS=BITS/8;"
+ "    var CHUNK_BYTES=CHUNK*CH*BPS;"
+ "    aReader=resp.body.getReader();"
+ "    aActive=true;aRetry=0;btnA.textContent='Audio ON';btnA.classList.add('on');"
+ "    nextT=actx.currentTime+getLat();"
+ "    var left=new Uint8Array(0);"
+ "    while(aActive){"
+ "      var res=await aReader.read();if(res.done||!aActive)break;"
+ "      var merged=new Uint8Array(left.length+res.value.length);"
+ "      merged.set(left);merged.set(res.value,left.length);"
+ "      var off=0;"
+ "      while(off+CHUNK_BYTES<=merged.length){"
+ "        var sl=merged.slice(off,off+CHUNK_BYTES);off+=CHUNK_BYTES;"
+ "        var buf=actx.createBuffer(CH,CHUNK,SR);"
+ "        var dv=new DataView(sl.buffer,sl.byteOffset);"
+ "        for(var ch=0;ch<CH;ch++){var c=buf.getChannelData(ch);for(var i=0;i<CHUNK;i++){"
+ "          var idx=(i*CH+ch)*BPS;"
+ "          if(BITS===8)c[i]=(dv.getUint8(idx)-128)/128;"
+ "          else if(BITS===32)c[i]=dv.getFloat32(idx,true);"
+ "          else c[i]=dv.getInt16(idx,true)/32768;"
+ "        }}"
+ "        var src=actx.createBufferSource();src.buffer=buf;src.connect(analyser);"
+ "        var now=actx.currentTime;"
+ "        if(nextT<now)nextT=now+getLat();"
+ "        if(nextT-now>MAX_AHEAD)nextT=now+getLat();"
+ "        src.start(nextT);nextT+=buf.duration;"
+ "      }"
+ "      left=merged.slice(off);"
+ "    }"
+ "    if(audioWanted&&aActive)schedAudio();"
+ "  }catch(e){"
+ "    var msg=e.message||String(e);"
+ "    if(msg.indexOf('NotAllowedError')>=0||msg.indexOf('not allowed')>=0)showToast('Audio blocked — click anywhere to enable',true);"
+ "    else if(msg.indexOf('503')>=0||msg.indexOf('501')>=0)showToast('Audio not available on this device',true);"
+ "    _stopAudioInternal();if(audioWanted)schedAudio();"
+ "  }"
+ "}"
+ "function schedAudio(){_stopAudioInternal();var d=Math.min(8000,500*Math.pow(2,aRetry));aRetry++;aTimer=setTimeout(function(){if(audioWanted)startAudio();},d);}"
+ "function _stopAudioInternal(){aActive=false;if(aReader){try{aReader.cancel();}catch(e){}aReader=null;}if(actx){try{actx.suspend();}catch(e){}actx=null;}abar.style.width='0%';}"
+ "function stopAudio(){audioWanted=false;if(aTimer){clearTimeout(aTimer);aTimer=null;}_stopAudioInternal();btnA.textContent='Audio OFF';btnA.classList.remove('on');}"
+ "function toggleAudio(){if(audioWanted){stopAudio();}else if(!aActive){audioWanted=true;aRetry=0;startAudio();}}"
+ "window.onerror=function(msg,src,line,col,err){handleErrEvent({level:'ERROR',source:'Browser',msg:msg+(line?' (line '+line+')':''),ts:Date.now()});return false;};"
+ "window.addEventListener('unhandledrejection',function(e){handleErrEvent({level:'WARNING',source:'Browser',msg:'Unhandled promise: '+(e.reason&&e.reason.message?e.reason.message:String(e.reason)),ts:Date.now()});});"
+ "startAudio().catch(function(){});"
+ "document.addEventListener('click',function boot(){document.removeEventListener('click',boot);if(!aActive&&audioWanted)startAudio();},{once:true});"
+ "</script></body></html>";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Screen Stream", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(int port) {
        PendingIntent stopPi = PendingIntent.getService(this, 0,
            new Intent(this, MiroirService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent mainPi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Miroir - Live")
            .setContentText("Streaming on port " + port)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(mainPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopCapture();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (serverThread    != null) serverThread.interrupt();
        if (rebuildExecutor != null) rebuildExecutor.shutdownNow();
    }
}
