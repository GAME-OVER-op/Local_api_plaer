package com.tabletplayer;

import android.content.Context;

import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Общая папка временного кэша просмотра и предзагрузки очереди. */
final class CacheFiles {
    private static final long STALE_MS = 12L * 60L * 60L * 1000L;
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE_SESSIONS = new AtomicInteger();

    private CacheFiles() {}


    static void acquireSession(Context context) {
        ACTIVE_SESSIONS.incrementAndGet();
        cleanup(context, null, false);
    }

    static void releaseSession(Context context) {
        int left = ACTIVE_SESSIONS.decrementAndGet();
        if (left <= 0) {
            ACTIVE_SESSIONS.set(0);
            cleanup(context, null, true);
        }
    }

    static File dir(Context context) {
        // Внутренний cacheDir обычно быстрее эмулированного внешнего накопителя.
        File base = context.getCacheDir();
        File dir = new File(base, "playback_queue");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File file(Context context, String base, String path) {
        return new File(dir(context), key(base, path) + ".part");
    }



    static void delete(Context context, String base, String path) {
        File file = file(context, base, path);
        synchronized (lock(base, path)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static Object lock(String base, String path) {
        String key = key(base, path);
        Object created = new Object();
        Object existing = LOCKS.putIfAbsent(key, created);
        return existing == null ? created : existing;
    }

    static String key(String base, String path) {
        String value = (base == null ? "" : base) + "\n" + (path == null ? "" : path);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] data = md.digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                out.append(String.format(java.util.Locale.US, "%02x", data[i] & 0xff));
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    static void cleanup(Context context, Set<File> keep, boolean removeAll) {
        File[] files = dir(context).listFiles();
        if (files == null) return;
        Set<String> protectedPaths = new HashSet<>();
        if (keep != null) {
            for (File file : keep) {
                if (file != null) protectedPaths.add(file.getAbsolutePath());
            }
        }
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (!file.isFile() || protectedPaths.contains(file.getAbsolutePath())) continue;
            if (removeAll || now - file.lastModified() > STALE_MS) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }
}
