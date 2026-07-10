package com.tabletplayer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class Store {
    static final String KEY_WATCHED = "watched";
    static final String KEY_POS = "positions";

    public static Set<String> watched(Context c) {
        Set<String> s = new HashSet<>();
        try {
            JSONArray a = new JSONArray(App.prefs(c).getString(KEY_WATCHED, "[]"));
            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return s;
    }

    public static boolean isWatched(Context c, String p) {
        return watched(c).contains(p);
    }

    public static void markWatched(Context c, String p) {
        try {
            Set<String> s = watched(c);
            if (s.add(p)) {
                JSONArray a = new JSONArray();
                for (String x : s) a.put(x);
                App.prefs(c).edit().putString(KEY_WATCHED, a.toString()).apply();
            }
        } catch (Exception ignored) {
        }
    }

    public static long getPos(Context c, String p) {
        try {
            JSONObject o = new JSONObject(App.prefs(c).getString(KEY_POS, "{}"));
            return o.optLong(p, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setPos(Context c, String p, long ms) {
        try {
            JSONObject o = new JSONObject(App.prefs(c).getString(KEY_POS, "{}"));
            o.put(p, ms);
            App.prefs(c).edit().putString(KEY_POS, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clearPos(Context c, String p) {
        try {
            JSONObject o = new JSONObject(App.prefs(c).getString(KEY_POS, "{}"));
            o.remove(p);
            App.prefs(c).edit().putString(KEY_POS, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
