package com.tabletplayer;

import android.content.Context;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Реестр временных файлов воспроизведения.
 *
 * В блоке B это только безопасная основа для будущей дозагрузки: менеджер
 * создаёт стабильные имена, хранит состояние записей и удаляет только те файлы,
 * которые не используются активным сеансом. Саму пользовательскую дозагрузку
 * включает следующий блок.
 */
public final class PlaybackCacheManager {
    public enum State {
        PARTIAL,
        READY,
        PLAYING,
        PREFETCH,
        RELEASED
    }

    public static final class Entry {
        public final String key;
        public final String base;
        public final String path;
        public final String name;
        public final File partFile;
        public final File finalFile;
        public final long createdAt;
        public long lastUsedAt;
        public long totalBytes = -1;
        // Непрерывно доступные байты от начала файла. Именно это безопасно отдавать libVLC.
        public long downloadedBytes = 0;
        // Сколько байт уже скачано суммарно всеми Range-потоками, включая будущие участки.
        public long cachedBytes = 0;
        public int generation = 0;
        public State state = State.PARTIAL;
        private int users = 0;

        private Entry(String key, String base, String path, String name, File partFile, File finalFile) {
            this.key = key;
            this.base = base;
            this.path = path;
            this.name = name;
            this.partFile = partFile;
            this.finalFile = finalFile;
            this.createdAt = System.currentTimeMillis();
            this.lastUsedAt = this.createdAt;
        }

        public synchronized void retain() {
            users++;
            lastUsedAt = System.currentTimeMillis();
        }

        public synchronized void release() {
            if (users > 0) users--;
            lastUsedAt = System.currentTimeMillis();
        }

        public synchronized boolean inUse() {
            return users > 0 || state == State.PLAYING;
        }
    }

    private static final PlaybackCacheManager INSTANCE = new PlaybackCacheManager();
    private final Map<String, Entry> entries = new HashMap<>();

    private PlaybackCacheManager() {
    }

    public static PlaybackCacheManager get() {
        return INSTANCE;
    }

    public synchronized Entry entryFor(Context ctx, String base, String path, String name) {
        String key = key(base, path);
        Entry e = entries.get(key);
        if (e != null) {
            e.lastUsedAt = System.currentTimeMillis();
            return e;
        }
        File dir = new File(cacheRoot(ctx), key);
        if (!dir.exists()) dir.mkdirs();
        String safe = safeName(name == null || name.length() == 0 ? "video" : name);
        File part = new File(dir, safe + ".part");
        File fin = new File(dir, safe);
        e = new Entry(key, base, path, name, part, fin);
        entries.put(key, e);
        return e;
    }

    public synchronized void markState(Entry entry, State state) {
        if (entry == null) return;
        entry.state = state;
        entry.lastUsedAt = System.currentTimeMillis();
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public synchronized void release(Entry entry) {
        if (entry == null) return;
        entry.release();
        if (entry.state != State.PLAYING && entry.state != State.READY && entry.state != State.PREFETCH) {
            entry.state = State.RELEASED;
        }
    }

    public synchronized void cleanupStale(Context ctx, long maxAgeMs) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.inUse()) continue;
            if (now - e.lastUsedAt < maxAgeMs) continue;
            deleteQuietly(e.partFile);
            deleteQuietly(e.finalFile);
            File parent = e.partFile.getParentFile();
            if (parent != null) parent.delete();
            it.remove();
        }

        File root = cacheRoot(ctx);
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (entries.containsKey(d.getName())) continue;
            if (now - d.lastModified() < maxAgeMs) continue;
            deleteTree(d);
        }
    }

    public synchronized void deleteEntry(Entry entry) {
        if (entry == null) return;
        entry.release();
        entry.state = State.RELEASED;
        entries.remove(entry.key);
        deleteQuietly(entry.partFile);
        deleteQuietly(entry.finalFile);
        File parent = entry.partFile.getParentFile();
        if (parent != null) parent.delete();
    }

    public synchronized void clearAll(Context ctx) {
        for (Entry e : new ArrayList<>(entries.values())) {
            e.state = State.RELEASED;
            deleteQuietly(e.partFile);
            deleteQuietly(e.finalFile);
            File parent = e.partFile.getParentFile();
            if (parent != null) parent.delete();
        }
        entries.clear();
        deleteTree(cacheRoot(ctx));
        cacheRoot(ctx);
    }

    public static File cacheRoot(Context ctx) {
        File root = new File(ctx.getCacheDir(), "playback-cache");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    private static String key(String base, String path) {
        return shortHash((base == null ? "" : base) + "|" + (path == null ? "" : path));
    }

    private static String safeName(String name) {
        return name.replace('/', '_').replace('\\', '_').replace('\0', '_');
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[24];
            for (int i = 0; i < 12; i++) {
                int v = b[i] & 0xff;
                out[i * 2] = hex[v >>> 4];
                out[i * 2 + 1] = hex[v & 15];
            }
            return new String(out);
        } catch (Exception e) {
            return String.valueOf(Math.abs(s.hashCode()));
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        deleteQuietly(f);
    }
}
