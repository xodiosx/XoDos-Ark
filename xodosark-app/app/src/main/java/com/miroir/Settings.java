package com.miroir;

import android.content.Context;
import android.content.SharedPreferences;

/** Thin wrapper around SharedPreferences so app settings survive across sessions. */
public class Settings {

    private static final String PREFS_NAME = "miroir_settings";

    public static final String KEY_QUALITY             = "quality";
    public static final String KEY_FPS                 = "fps";
    public static final String KEY_AUDIO_ENABLED        = "audio_enabled";
    public static final String KEY_AUDIO_SAMPLE_RATE    = "audio_sample_rate";
    public static final String KEY_AUDIO_CHANNELS       = "audio_channels";
    public static final String KEY_AUDIO_ENCODING       = "audio_encoding";
    public static final String KEY_PORT                 = "port";
    public static final String KEY_AUTO_RESTART         = "auto_restart";
    public static final String KEY_AUTH_MODE            = "auth_mode";
    public static final String KEY_PIN                  = "pin";
    public static final String KEY_BASIC_USER           = "basic_user";
    public static final String KEY_BASIC_PASS           = "basic_pass";
    public static final String KEY_HTTPS_ENABLED        = "https_enabled";
    public static final String KEY_KEEP_AWAKE           = "keep_awake";

    public static final String KEY_MAX_WIDTH            = "max_width";
    public static final String KEY_MAX_HEIGHT           = "max_height";
    public static final String KEY_BITRATE              = "bitrate";

    private final SharedPreferences prefs;

    public Settings(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getInt(String key, int defValue) { return prefs.getInt(key, defValue); }
    public void putInt(String key, int value)    { prefs.edit().putInt(key, value).apply(); }

    public boolean getBoolean(String key, boolean defValue) { return prefs.getBoolean(key, defValue); }
    public void putBoolean(String key, boolean value)       { prefs.edit().putBoolean(key, value).apply(); }

    public String getString(String key, String defValue) { return prefs.getString(key, defValue); }
    public void putString(String key, String value)       { prefs.edit().putString(key, value).apply(); }
}
