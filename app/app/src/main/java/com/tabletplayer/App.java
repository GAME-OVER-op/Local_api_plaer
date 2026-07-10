package com.tabletplayer;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatDelegate;

public class App extends Application {
    public static final String PREFS = "tablet_player";
    public static final String KEY_THEME = "theme";

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme(isDark(this));
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isDark(Context c) {
        return "dark".equals(prefs(c).getString(KEY_THEME, "light"));
    }

    public static void setDark(Context c, boolean dark) {
        prefs(c).edit().putString(KEY_THEME, dark ? "dark" : "light").apply();
        applyTheme(dark);
    }

    public static void applyTheme(boolean dark) {
        AppCompatDelegate.setDefaultNightMode(
                dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }

    public static String deviceId(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.isEmpty()) id = "unknown-device";
        return id;
    }

    public static String deviceName() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String mo = Build.MODEL == null ? "" : Build.MODEL;
        StringBuilder b = new StringBuilder();
        String n = (m + " " + mo).trim();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch >= 32 && ch < 127) b.append(ch);
        }
        String r = b.toString().trim();
        return r.isEmpty() ? "Android" : r;
    }

    public static void auth(java.net.HttpURLConnection c, Context ctx) {
        c.setRequestProperty("X-Device-Id", deviceId(ctx));
        c.setRequestProperty("X-Device-Name", deviceName());
    }
}
