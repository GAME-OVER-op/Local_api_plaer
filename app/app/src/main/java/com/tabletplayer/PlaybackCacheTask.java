package com.tabletplayer;

import android.content.Context;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;

/**
 * Один последовательный загрузчик для текущего воспроизведения или предзагрузки.
 * Он не управляет libVLC напрямую: только наполняет временный файл и сообщает
 * доступный объём локальному прокси.
 */
public final class PlaybackCacheTask implements PlaybackProxyServer.DataSource, AutoCloseable {
    public interface Listener {
        void onCacheProgress(PlaybackCacheTask task);
        void onCacheReady(PlaybackCacheTask task, boolean early);
        void onCacheComplete(PlaybackCacheTask task);
        void onCacheFallback(PlaybackCacheTask task, String reason);
        void onCacheError(PlaybackCacheTask task, Exception error);
    }

    private static final long MB = 1024L * 1024L;
    public static final long START_PERCENT_LIMIT = 30L;
    public static final long START_MAX_BYTES = 300L * MB;
    public static final long MIN_EARLY_START_BYTES = 64L * MB;
    private static final long PREPARE_TIMEOUT_MS = 30000L;
    private static final long SPEED_SAMPLE_WINDOW_MS = 8000L;
    private static final long MIN_EARLY_SPEED_BYTES_PER_SEC = 1536L * 1024L;
    private static final int BUFFER_SIZE = 256 * 1024;

    private final Context context;
    private final PlaybackCacheManager.Entry entry;
    private final Listener listener;
    private final boolean prefetch;
    private final Object lock = new Object();
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private volatile boolean cancelled;
    private volatile boolean complete;
    private volatile boolean finished;
    private volatile boolean readyNotified;
    private volatile boolean fallbackNotified;
    private volatile Exception error;
    private volatile Thread thread;
    private volatile TransferCoordinator.Lease lease;
    private volatile HttpURLConnection connection;

    private long startAtMs;
    private long lastProgressNotifyMs;

    public PlaybackCacheTask(Context context, PlaybackCacheManager.Entry entry, boolean prefetch, Listener listener) {
        this.context = context.getApplicationContext();
        this.entry = entry;
        this.prefetch = prefetch;
        this.listener = listener;
    }

    public PlaybackCacheManager.Entry entry() {
        return entry;
    }

    public long downloadedBytes() {
        synchronized (lock) {
            return entry.downloadedBytes;
        }
    }

    @Override
    public long totalBytes() {
        synchronized (lock) {
            return entry.totalBytes;
        }
    }

    @Override
    public long availableBytes() {
        return downloadedBytes();
    }

    @Override
    public boolean complete() {
        return complete;
    }

    public boolean failed() {
        return error != null;
    }

    public boolean finished() {
        return finished;
    }

    public long prepareTargetBytes() {
        long total = totalBytes();
        if (total <= 0) return START_MAX_BYTES;
        long percent = total * START_PERCENT_LIMIT / 100L;
        if (percent <= 0) percent = total;
        return Math.min(percent, START_MAX_BYTES);
    }

    public int percent() {
        long total = totalBytes();
        if (total <= 0) return 0;
        long pct = downloadedBytes() * 100L / total;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return (int) pct;
    }

    public long bytesForTime(long ms, long durationMs) {
        long total = totalBytes();
        if (durationMs <= 0 || total <= 0 || ms <= 0) return 0;
        long b = total * ms / durationMs;
        return Math.max(0, Math.min(total, b));
    }

    public boolean hasBytesForTime(long ms, long durationMs, long extraBytes) {
        long total = totalBytes();
        if (total <= 0 || durationMs <= 0) return true;
        long need = bytesForTime(ms, durationMs) + Math.max(0, extraBytes);
        if (need > total) need = total;
        return downloadedBytes() >= need;
    }

    public long recentSpeedBytesPerSec() {
        synchronized (lock) {
            if (samples.size() < 2) return 0;
            Sample first = samples.peekFirst();
            Sample last = samples.peekLast();
            long dt = last.timeMs - first.timeMs;
            if (dt <= 0) return 0;
            long db = last.bytes - first.bytes;
            return Math.max(0, db * 1000L / dt);
        }
    }

    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                runDownload();
            }
        }, prefetch ? "PlaybackPrefetch" : "PlaybackCacheDownload");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void runDownload() {
        startAtMs = System.currentTimeMillis();
        try {
            PlaybackCacheManager.get().markState(entry, prefetch ? PlaybackCacheManager.State.PREFETCH : PlaybackCacheManager.State.PARTIAL);
            entry.retain();
            long total = fetchTotalBytes();
            if (total <= 0) {
                notifyFallback("сервер не сообщил размер файла");
                return;
            }
            synchronized (lock) {
                entry.totalBytes = total;
            }
            downloadSequential(total);
        } catch (Exception e) {
            error = e;
            if (!cancelled && listener != null) listener.onCacheError(this, e);
        } finally {
            finished = true;
            if (connection != null) connection.disconnect();
            if (lease != null) lease.close();
            entry.release();
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    private long fetchTotalBytes() throws Exception {
        HttpURLConnection c = null;
        TransferCoordinator.Lease metaLease = null;
        try {
            metaLease = TransferCoordinator.get().tryAcquire(TransferCoordinator.Priority.PLAYBACK_METADATA, "cache-size", 5000L);
            if (metaLease == null) return -1;
            c = (HttpURLConnection) new URL(entry.base + "/download?path=" + Util.enc(entry.path)).openConnection();
            App.auth(c, context);
            c.setRequestProperty("Range", "bytes=0-0");
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            int code = c.getResponseCode();
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                long total = parseContentRangeTotal(cr);
                if (total > 0) return total;
            }
            if (code == 200 || code == 206) {
                long len = c.getContentLength();
                if (len > 0) return len;
            }
            return -1;
        } finally {
            if (c != null) c.disconnect();
            if (metaLease != null) metaLease.close();
        }
    }

    private void downloadSequential(long total) throws Exception {
        lease = TransferCoordinator.get().acquire(prefetch ? TransferCoordinator.Priority.PREFETCH : TransferCoordinator.Priority.PLAYBACK_CACHE, prefetch ? "prefetch" : "playback-cache");
        long existing = entry.partFile.exists() ? entry.partFile.length() : 0;
        if (existing > total) existing = 0;
        synchronized (lock) {
            entry.downloadedBytes = existing;
            addSampleLocked(existing);
        }
        if (existing >= total) {
            completeFile(total);
            return;
        }

        connection = (HttpURLConnection) new URL(entry.base + "/download?path=" + Util.enc(entry.path)).openConnection();
        App.auth(connection, context);
        if (existing > 0) connection.setRequestProperty("Range", "bytes=" + existing + "-");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(40000);
        int code = connection.getResponseCode();
        if (!(code == 200 || code == 206)) throw new RuntimeException("HTTP " + code);
        if (existing > 0 && code == 200) existing = 0;

        InputStream in = null;
        RandomAccessFile out = null;
        try {
            in = connection.getInputStream();
            out = new RandomAccessFile(entry.partFile, "rw");
            if (existing == 0) out.setLength(0);
            out.seek(existing);
            byte[] buf = new byte[BUFFER_SIZE];
            long written = existing;
            int read;
            while (!cancelled && (read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                written += read;
                synchronized (lock) {
                    entry.downloadedBytes = written;
                    addSampleLocked(written);
                    lock.notifyAll();
                }
                maybeNotifyProgress();
                maybeNotifyReady();
                if (!prefetch) maybeTimeoutFallback();
            }
            if (!cancelled && written >= total) completeFile(total);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    private void completeFile(long total) {
        complete = true;
        synchronized (lock) {
            entry.downloadedBytes = total;
            entry.totalBytes = total;
            lock.notifyAll();
        }
        if (!entry.finalFile.exists()) {
            if (!entry.partFile.renameTo(entry.finalFile)) {
                copyFile(entry.partFile, entry.finalFile);
                entry.partFile.delete();
            }
        }
        PlaybackCacheManager.get().markState(entry, PlaybackCacheManager.State.READY);
        if (!readyNotified && listener != null && !prefetch) {
            readyNotified = true;
            listener.onCacheReady(this, false);
        }
        if (listener != null) listener.onCacheComplete(this);
    }

    private void maybeNotifyReady() {
        if (prefetch || readyNotified || fallbackNotified || cancelled) return;
        long downloaded = downloadedBytes();
        long target = prepareTargetBytes();
        long elapsed = System.currentTimeMillis() - startAtMs;
        boolean targetReached = target > 0 && downloaded >= target;
        boolean early = downloaded >= MIN_EARLY_START_BYTES
                && elapsed >= 5000L
                && recentSpeedBytesPerSec() >= MIN_EARLY_SPEED_BYTES_PER_SEC;
        boolean timeoutButUsable = elapsed >= PREPARE_TIMEOUT_MS
                && downloaded >= MIN_EARLY_START_BYTES
                && recentSpeedBytesPerSec() >= 1024L * 1024L;
        if (targetReached || early || timeoutButUsable) {
            readyNotified = true;
            PlaybackCacheManager.get().markState(entry, PlaybackCacheManager.State.PLAYING);
            if (listener != null) listener.onCacheReady(this, !targetReached);
        }
    }

    private void maybeTimeoutFallback() {
        if (readyNotified || fallbackNotified || cancelled) return;
        long elapsed = System.currentTimeMillis() - startAtMs;
        if (elapsed < PREPARE_TIMEOUT_MS) return;
        notifyFallback("подготовка не успела за 30 секунд");
    }

    private void notifyFallback(String reason) {
        if (fallbackNotified || readyNotified || cancelled || prefetch) return;
        fallbackNotified = true;
        if (listener != null) listener.onCacheFallback(this, reason);
    }

    private void maybeNotifyProgress() {
        long now = System.currentTimeMillis();
        if (now - lastProgressNotifyMs < 500L) return;
        lastProgressNotifyMs = now;
        if (listener != null) listener.onCacheProgress(this);
    }

    private void addSampleLocked(long bytes) {
        long now = System.currentTimeMillis();
        samples.addLast(new Sample(now, bytes));
        while (samples.size() > 2 && now - samples.peekFirst().timeMs > SPEED_SAMPLE_WINDOW_MS) {
            samples.removeFirst();
        }
    }

    @Override
    public boolean waitForBytes(long bytes, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!cancelled && error == null && entry.downloadedBytes < bytes && !complete) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                lock.wait(Math.min(left, 1000L));
            }
            return entry.downloadedBytes >= bytes || complete;
        }
    }

    @Override
    public java.io.File file() {
        if (entry.finalFile.exists()) return entry.finalFile;
        return entry.partFile;
    }

    @Override
    public void close() {
        cancelled = true;
        if (connection != null) connection.disconnect();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private static long parseContentRangeTotal(String cr) {
        if (cr == null) return -1;
        int slash = cr.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= cr.length()) return -1;
        try {
            return Long.parseLong(cr.substring(slash + 1).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void copyFile(java.io.File src, java.io.File dst) {
        java.io.FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        } catch (Exception ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    private static final class Sample {
        final long timeMs;
        final long bytes;

        Sample(long timeMs, long bytes) {
            this.timeMs = timeMs;
            this.bytes = bytes;
        }
    }
}
