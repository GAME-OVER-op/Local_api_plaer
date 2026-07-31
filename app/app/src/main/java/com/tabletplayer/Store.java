package com.tabletplayer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class Store {
    static final String KEY_WATCHED = "watched";
    static final String KEY_POS = "positions";
    static final String KEY_VOLUME = "boost_volume";
    static final String KEY_ASPECT = "aspect_mode";
    static final String KEY_DECODER_MODE = "decoder_mode";

    private static String watchedRaw = null;
    private static Set<String> watchedCache = null;
    private static String posRaw = null;
    private static JSONObject posCache = null;

    public static synchronized Set<String> watched(Context c) {
        String raw = App.prefs(c).getString(KEY_WATCHED, "[]");
        if (watchedCache != null && raw.equals(watchedRaw)) {
            return new HashSet<>(watchedCache);
        }
        Set<String> s = new HashSet<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
        } catch (Exception ignored) {
        }
        watchedRaw = raw;
        watchedCache = new HashSet<>(s);
        return s;
    }

    public static synchronized boolean isWatched(Context c, String p) {
        return watched(c).contains(p);
    }

    public static synchronized void markWatched(Context c, String p) {
        try {
            Set<String> s = watched(c);
            if (s.add(p)) {
                JSONArray a = new JSONArray();
                for (String x : s) a.put(x);
                String raw = a.toString();
                App.prefs(c).edit().putString(KEY_WATCHED, raw).apply();
                watchedRaw = raw;
                watchedCache = new HashSet<>(s);
            }
        } catch (Exception ignored) {
        }
    }

    private static synchronized JSONObject positions(Context c) {
        String raw = App.prefs(c).getString(KEY_POS, "{}");
        if (posCache != null && raw.equals(posRaw)) {
            return posCache;
        }
        try {
            posCache = new JSONObject(raw);
            posRaw = raw;
        } catch (Exception e) {
            posCache = new JSONObject();
            posRaw = "{}";
        }
        return posCache;
    }

    public static synchronized long getPos(Context c, String p) {
        try {
            return positions(c).optLong(p, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static synchronized void setPos(Context c, String p, long ms) {
        try {
            JSONObject o = positions(c);
            o.put(p, ms);
            posRaw = o.toString();
            App.prefs(c).edit().putString(KEY_POS, posRaw).apply();
        } catch (Exception ignored) {
        }
    }

    public static synchronized void clearPos(Context c, String p) {
        try {
            JSONObject o = positions(c);
            o.remove(p);
            posRaw = o.toString();
            App.prefs(c).edit().putString(KEY_POS, posRaw).apply();
        } catch (Exception ignored) {
        }
    }

    public static int getVolume(Context c, int def) {
        return App.prefs(c).getInt(KEY_VOLUME, def);
    }

    public static void setVolume(Context c, int v) {
        App.prefs(c).edit().putInt(KEY_VOLUME, v).apply();
    }

    public static int getAspect(Context c, int def) {
        return App.prefs(c).getInt(KEY_ASPECT, def);
    }

    public static void setAspect(Context c, int v) {
        App.prefs(c).edit().putInt(KEY_ASPECT, v).apply();
    }

    public static int getDecoderMode(Context c, int def) {
        return App.prefs(c).getInt(KEY_DECODER_MODE, def);
    }

    public static void setDecoderMode(Context c, int v) {
        App.prefs(c).edit().putInt(KEY_DECODER_MODE, v).apply();
    }
}
