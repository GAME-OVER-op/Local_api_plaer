package com.tabletplayer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Store {
    static final String KEY_WATCHED = "watched";
    static final String KEY_POS = "positions";
    static final String KEY_DURATION = "durations";
    static final String KEY_VOLUME = "boost_volume";
    static final String KEY_ASPECT = "aspect_mode";

    private static final Object LOCK = new Object();
    private static boolean loaded = false;
    private static final Set<String> WATCHED = new HashSet<>();
    private static final Map<String, Long> POSITIONS = new HashMap<>();
    private static final Map<String, Long> DURATIONS = new HashMap<>();

    private static void ensureLoaded(Context c) {
        synchronized (LOCK) {
            if (loaded) return;
            try {
                JSONArray a = new JSONArray(App.prefs(c).getString(KEY_WATCHED, "[]"));
                for (int i = 0; i < a.length(); i++) WATCHED.add(a.getString(i));
            } catch (Exception ignored) {
            }
            try {
                JSONObject o = new JSONObject(App.prefs(c).getString(KEY_POS, "{}"));
                JSONArray names = o.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String key = names.getString(i);
                        long value = o.optLong(key, 0);
                        if (value > 0) POSITIONS.put(key, value);
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                JSONObject o = new JSONObject(App.prefs(c).getString(KEY_DURATION, "{}"));
                JSONArray names = o.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String key = names.getString(i);
                        long value = o.optLong(key, 0);
                        if (value > 0) DURATIONS.put(key, value);
                    }
                }
            } catch (Exception ignored) {
            }
            loaded = true;
        }
    }

    public static Set<String> watched(Context c) {
        ensureLoaded(c);
        synchronized (LOCK) {
            return new HashSet<>(WATCHED);
        }
    }

    public static boolean isWatched(Context c, String p) {
        ensureLoaded(c);
        synchronized (LOCK) {
            return WATCHED.contains(p);
        }
    }

    public static void markWatched(Context c, String p) {
        if (p == null || p.isEmpty()) return;
        ensureLoaded(c);
        synchronized (LOCK) {
            if (!WATCHED.add(p)) return;
            JSONArray a = new JSONArray();
            for (String x : WATCHED) a.put(x);
            App.prefs(c).edit().putString(KEY_WATCHED, a.toString()).apply();
        }
    }

    public static long getPos(Context c, String p) {
        ensureLoaded(c);
        synchronized (LOCK) {
            Long value = POSITIONS.get(p);
            return value == null ? 0 : value;
        }
    }

    public static void setPos(Context c, String p, long ms) {
        if (p == null || p.isEmpty()) return;
        if (ms <= 0) {
            clearPos(c, p);
            return;
        }
        ensureLoaded(c);
        synchronized (LOCK) {
            Long old = POSITIONS.get(p);
            if (old != null && old == ms) return;
            POSITIONS.put(p, ms);
            persistPositions(c);
        }
    }

    public static void clearPos(Context c, String p) {
        if (p == null || p.isEmpty()) return;
        ensureLoaded(c);
        synchronized (LOCK) {
            if (POSITIONS.remove(p) != null) persistPositions(c);
        }
    }

    private static void persistPositions(Context c) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, Long> e : POSITIONS.entrySet()) o.put(e.getKey(), e.getValue());
            App.prefs(c).edit().putString(KEY_POS, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static long getDuration(Context c, String p) {
        ensureLoaded(c);
        synchronized (LOCK) {
            Long value = DURATIONS.get(p);
            return value == null ? 0 : value;
        }
    }

    public static void setDuration(Context c, String p, long ms) {
        if (p == null || p.isEmpty() || ms <= 0) return;
        ensureLoaded(c);
        synchronized (LOCK) {
            Long old = DURATIONS.get(p);
            if (old != null && Math.abs(old - ms) < 1000) return;
            DURATIONS.put(p, ms);
            JSONObject o = new JSONObject();
            try {
                for (Map.Entry<String, Long> e : DURATIONS.entrySet()) o.put(e.getKey(), e.getValue());
                App.prefs(c).edit().putString(KEY_DURATION, o.toString()).apply();
            } catch (Exception ignored) {
            }
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
}
