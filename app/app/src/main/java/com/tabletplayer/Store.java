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
    static final String KEY_LIBVLC_CATCH_UP_FRAMES = "libvlc_catch_up_frames";
    static final String KEY_LIBVLC_AVCODEC_FAST = "libvlc_avcodec_fast";
    static final String KEY_LIBVLC_SKIP_LOOP_FILTER = "libvlc_skip_loop_filter";
    static final String KEY_LIBVLC_NETWORK_CACHING = "libvlc_network_caching";
    static final String KEY_LIBVLC_FILE_CACHING = "libvlc_file_caching";
    static final String KEY_LIBVLC_LOCAL_CACHING = "libvlc_local_caching";
    static final String KEY_PLAYBACK_CACHE_THREADS = "playback_cache_threads";
    static final String KEY_PLAYBACK_PREFETCH_THREADS = "playback_prefetch_threads";
    static final String KEY_CONTENT_LOAD_MODE = "content_load_mode";

    public static final int CONTENT_LOAD_AUTO = 0;
    public static final int CONTENT_LOAD_LOCAL_CACHE = 1;
    public static final int CONTENT_LOAD_DIRECT = 2;

    public static final int LIBVLC_DEFAULT_CACHING_MS = 4000;
    private static final int LIBVLC_MIN_CACHING_MS = 300;
    private static final int LIBVLC_MAX_CACHING_MS = 60000;
    public static final int PLAYBACK_CACHE_DEFAULT_THREADS = 8;
    public static final int PLAYBACK_PREFETCH_DEFAULT_THREADS = 3;
    public static final int PLAYBACK_CACHE_MAX_THREADS = 12;
    public static final int PLAYBACK_PREFETCH_MAX_THREADS = 6;

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

    public static boolean getLibVlcCatchUpFrames(Context c) {
        return App.prefs(c).getBoolean(KEY_LIBVLC_CATCH_UP_FRAMES, true);
    }

    public static void setLibVlcCatchUpFrames(Context c, boolean enabled) {
        App.prefs(c).edit().putBoolean(KEY_LIBVLC_CATCH_UP_FRAMES, enabled).apply();
    }

    public static boolean getLibVlcAvcodecFast(Context c) {
        return App.prefs(c).getBoolean(KEY_LIBVLC_AVCODEC_FAST, false);
    }

    public static void setLibVlcAvcodecFast(Context c, boolean enabled) {
        App.prefs(c).edit().putBoolean(KEY_LIBVLC_AVCODEC_FAST, enabled).apply();
    }

    public static String getLibVlcSkipLoopFilter(Context c) {
        String v = App.prefs(c).getString(KEY_LIBVLC_SKIP_LOOP_FILTER, "off");
        if ("nonref".equals(v) || "bidir".equals(v) || "all".equals(v)) return v;
        return "off";
    }

    public static void setLibVlcSkipLoopFilter(Context c, String value) {
        String v = ("nonref".equals(value) || "bidir".equals(value) || "all".equals(value)) ? value : "off";
        App.prefs(c).edit().putString(KEY_LIBVLC_SKIP_LOOP_FILTER, v).apply();
    }

    public static int getLibVlcNetworkCaching(Context c) {
        return clampCaching(App.prefs(c).getInt(KEY_LIBVLC_NETWORK_CACHING, LIBVLC_DEFAULT_CACHING_MS));
    }

    public static void setLibVlcNetworkCaching(Context c, int value) {
        App.prefs(c).edit().putInt(KEY_LIBVLC_NETWORK_CACHING, clampCaching(value)).apply();
    }

    public static int getLibVlcFileCaching(Context c) {
        return clampCaching(App.prefs(c).getInt(KEY_LIBVLC_FILE_CACHING, LIBVLC_DEFAULT_CACHING_MS));
    }

    public static void setLibVlcFileCaching(Context c, int value) {
        App.prefs(c).edit().putInt(KEY_LIBVLC_FILE_CACHING, clampCaching(value)).apply();
    }

    public static int getLibVlcLocalCaching(Context c) {
        return clampCaching(App.prefs(c).getInt(KEY_LIBVLC_LOCAL_CACHING, LIBVLC_DEFAULT_CACHING_MS));
    }

    public static void setLibVlcLocalCaching(Context c, int value) {
        App.prefs(c).edit().putInt(KEY_LIBVLC_LOCAL_CACHING, clampCaching(value)).apply();
    }

    public static void resetLibVlcSettings(Context c) {
        App.prefs(c).edit()
                .putInt(KEY_DECODER_MODE, 0)
                .putBoolean(KEY_LIBVLC_CATCH_UP_FRAMES, true)
                .putBoolean(KEY_LIBVLC_AVCODEC_FAST, false)
                .putString(KEY_LIBVLC_SKIP_LOOP_FILTER, "off")
                .putInt(KEY_LIBVLC_NETWORK_CACHING, LIBVLC_DEFAULT_CACHING_MS)
                .putInt(KEY_LIBVLC_FILE_CACHING, LIBVLC_DEFAULT_CACHING_MS)
                .putInt(KEY_LIBVLC_LOCAL_CACHING, LIBVLC_DEFAULT_CACHING_MS)
                .putInt(KEY_CONTENT_LOAD_MODE, CONTENT_LOAD_AUTO)
                .putInt(KEY_PLAYBACK_CACHE_THREADS, PLAYBACK_CACHE_DEFAULT_THREADS)
                .putInt(KEY_PLAYBACK_PREFETCH_THREADS, PLAYBACK_PREFETCH_DEFAULT_THREADS)
                .apply();
    }

    public static int getContentLoadMode(Context c) {
        return clampContentLoadMode(App.prefs(c).getInt(KEY_CONTENT_LOAD_MODE, CONTENT_LOAD_AUTO));
    }

    public static void setContentLoadMode(Context c, int value) {
        App.prefs(c).edit().putInt(KEY_CONTENT_LOAD_MODE, clampContentLoadMode(value)).apply();
    }

    public static int clampContentLoadMode(int value) {
        if (value == CONTENT_LOAD_LOCAL_CACHE || value == CONTENT_LOAD_DIRECT) return value;
        return CONTENT_LOAD_AUTO;
    }

    public static int getPlaybackCacheThreads(Context c) {
        return clampPlaybackCacheThreads(App.prefs(c).getInt(KEY_PLAYBACK_CACHE_THREADS, PLAYBACK_CACHE_DEFAULT_THREADS));
    }

    public static void setPlaybackCacheThreads(Context c, int value) {
        App.prefs(c).edit().putInt(KEY_PLAYBACK_CACHE_THREADS, clampPlaybackCacheThreads(value)).apply();
    }

    public static int getPlaybackPrefetchThreads(Context c) {
        return clampPlaybackPrefetchThreads(App.prefs(c).getInt(KEY_PLAYBACK_PREFETCH_THREADS, PLAYBACK_PREFETCH_DEFAULT_THREADS));
    }

    public static void setPlaybackPrefetchThreads(Context c, int value) {
        App.prefs(c).edit().putInt(KEY_PLAYBACK_PREFETCH_THREADS, clampPlaybackPrefetchThreads(value)).apply();
    }

    public static int clampPlaybackCacheThreads(int value) {
        if (value < 3) return 3;
        if (value > PLAYBACK_CACHE_MAX_THREADS) return PLAYBACK_CACHE_MAX_THREADS;
        return value;
    }

    public static int clampPlaybackPrefetchThreads(int value) {
        if (value < 1) return 1;
        if (value > PLAYBACK_PREFETCH_MAX_THREADS) return PLAYBACK_PREFETCH_MAX_THREADS;
        return value;
    }

    public static int clampCaching(int value) {
        if (value < LIBVLC_MIN_CACHING_MS) return LIBVLC_MIN_CACHING_MS;
        if (value > LIBVLC_MAX_CACHING_MS) return LIBVLC_MAX_CACHING_MS;
        return value;
    }
}
