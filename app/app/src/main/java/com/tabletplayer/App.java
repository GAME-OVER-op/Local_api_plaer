package com.tabletplayer;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

public class App extends Application {

    public static final String PREFS = "tablet_player";
    public static final String KEY_THEME = "theme"; // "light" | "dark"

    @Override
    public void onCreate() {
        super.onCreate();
        applyTheme(this);
    }

    public static boolean isDark(Context c) {
        return "dark".equals(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, "light"));
    }

    public static void applyTheme(Context c) {
        AppCompatDelegate.setDefaultNightMode(isDark(c)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    public static void setDark(Context c, boolean dark) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, dark ? "dark" : "light")
                .apply();
        AppCompatDelegate.setDefaultNightMode(dark
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }
}
