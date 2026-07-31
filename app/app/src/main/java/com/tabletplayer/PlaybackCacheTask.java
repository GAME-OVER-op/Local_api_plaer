package com.tabletplayer;

import android.content.Context;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Range-загрузчик для локального playback-cache.
 *
 * Важное разделение:
 * - cachedBytes — сколько скачано суммарно всеми потоками;
 * - downloadedBytes/availableBytes — сколько байт непрерывно доступно от начала файла.
 *
 * Локальный proxy и libVLC видят только непрерывный участок, поэтому даже при
 * многопоточном скачивании файл не отдаётся с дырками.
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
    private static final int MAX_CHUNK_RETRIES = 3;

    private final Context context;
    private final PlaybackCacheManager.Entry entry;
    private final Listener listener;
    private final boolean prefetch;
    private final Object lock = new Object();
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final List<HttpURLConnection> activeConnections = new ArrayList<>();

    private volatile boolean cancelled;
    private volatile boolean complete;
    private volatile boolean finished;
    private volatile boolean readyNotified;
    private volatile boolean fallbackNotified;
    private volatile Exception error;
    private volatile Thread thread;

    private long startAtMs;
    private long lastProgressNotifyMs;
    private boolean[] chunkDone;
    private long[] chunkStart;
    private long[] chunkEnd;
    private int contiguousChunkIndex;

    public PlaybackCacheTask(Context context, PlaybackCacheManager.Entry entry, boolean prefetch, Listener listener) {
        this.context = context.getApplicationContext();
        this.entry = entry;
        this.prefetch = prefetch;
        this.listener = listener;
    }

    public PlaybackCacheManager.Entry entry() {
        return entry;
    }

    /** Непрерывно доступно от начала файла. */
    public long downloadedBytes() {
        synchronized (lock) {
            return entry.downloadedBytes;
        }
    }

    /** Скачано суммарно всеми Range-потоками. Может быть больше downloadedBytes(). */
    public long cachedBytes() {
        synchronized (lock) {
            return Math.max(entry.cachedBytes, entry.downloadedBytes);
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

    public int workerCount() {
        if (prefetch) return Store.getPlaybackPrefetchThreads(context);
        return Store.getPlaybackCacheThreads(context);
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
        long pct = cachedBytes() * 100L / total;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return (int) pct;
    }

    public int availablePercent() {
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
        thread.setPriority(prefetch ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY);
        thread.start();
    }

    private void runDownload() {
        startAtMs = System.currentTimeMillis();
        PlayerDiagnostics.log(context, prefetch ? "prefetch" : "cache", "start path=" + entry.path + " file=" + entry.partFile.getName());
        try {
            PlaybackCacheManager.get().markState(entry, prefetch ? PlaybackCacheManager.State.PREFETCH : PlaybackCacheManager.State.PARTIAL);
            entry.retain();
            long total = fetchTotalBytes();
            if (total <= 0) {
                PlayerDiagnostics.log(context, prefetch ? "prefetch" : "cache", "no total path=" + entry.path);
                notifyFallback("сервер не сообщил размер файла");
                return;
            }
            synchronized (lock) {
                entry.totalBytes = total;
                entry.downloadedBytes = 0;
                entry.cachedBytes = 0;
                addSampleLocked(0);
            }
            PlayerDiagnostics.log(context, prefetch ? "prefetch" : "cache", "total=" + total + " target=" + prepareTargetBytes() + " workers=" + workerCount() + " max=" + TransferCoordinator.get().maxRemoteTransfers());
            downloadParallel(total);
        } catch (Exception e) {
            error = e;
            PlayerDiagnostics.log(context, prefetch ? "prefetch-error" : "cache-error", e);
            if (!cancelled && listener != null) listener.onCacheError(this, e);
        } finally {
            finished = true;
            disconnectAll();
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
            registerConnection(c);
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
            if (c != null) {
                unregisterConnection(c);
                c.disconnect();
            }
            if (metaLease != null) metaLease.close();
        }
    }

    private void downloadParallel(long total) throws Exception {
        prepareChunkTable(total);
        if (entry.partFile.exists()) entry.partFile.delete();
        RandomAccessFile init = new RandomAccessFile(entry.partFile, "rw");
        try {
            init.setLength(total);
        } finally {
            init.close();
        }

        int workers = Math.max(1, Math.min(workerCount(), chunkDone.length));
        final AtomicInteger nextChunk = new AtomicInteger(0);
        final AtomicBoolean workerFailed = new AtomicBoolean(false);
        ArrayList<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            final int workerIndex = i;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWorker(total, nextChunk, workerFailed, workerIndex);
                }
            }, (prefetch ? "PrefetchRange-" : "CacheRange-") + i);
            t.setDaemon(true);
            t.setPriority(prefetch ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY);
            threads.add(t);
            t.start();
        }

        for (Thread t : threads) {
            while (t.isAlive()) {
                try {
                    t.join(1000L);
                } catch (InterruptedException e) {
                    cancelled = true;
                    Thread.currentThread().interrupt();
                    break;
                }
                maybeNotifyProgress();
                if (!prefetch) {
                    maybeNotifyReady();
                    maybeTimeoutFallback();
                }
                if (cancelled || error != null) break;
            }
        }

        if (error != null) throw error;
        if (!cancelled && allChunksComplete()) completeFile(total);
    }

    private void runWorker(long total, AtomicInteger nextChunk, AtomicBoolean workerFailed, int workerIndex) {
        while (!cancelled && error == null && !workerFailed.get()) {
            int idx = nextChunk.getAndIncrement();
            if (idx >= chunkDone.length) return;
            boolean ok = false;
            Exception last = null;
            for (int attempt = 1; attempt <= MAX_CHUNK_RETRIES && !cancelled; attempt++) {
                try {
                    downloadChunk(idx, chunkStart[idx], chunkEnd[idx]);
                    ok = true;
                    break;
                } catch (Exception e) {
                    last = e;
                    PlayerDiagnostics.log(context, prefetch ? "prefetch-retry" : "cache-retry", "worker=" + workerIndex + " chunk=" + idx + " attempt=" + attempt + " err=" + e.getMessage());
                    try { Thread.sleep(200L * attempt); } catch (InterruptedException ignored) { break; }
                }
            }
            if (!ok && !cancelled) {
                error = last == null ? new RuntimeException("chunk failed") : last;
                workerFailed.set(true);
                cancelled = true;
                disconnectAll();
                synchronized (lock) {
                    lock.notifyAll();
                }
                return;
            }
        }
    }

    private void downloadChunk(int idx, long start, long end) throws Exception {
        TransferCoordinator.Lease l = null;
        HttpURLConnection c = null;
        InputStream in = null;
        RandomAccessFile out = null;
        try {
            l = TransferCoordinator.get().acquire(prefetch ? TransferCoordinator.Priority.PREFETCH : TransferCoordinator.Priority.PLAYBACK_CACHE, prefetch ? "prefetch-range" : "cache-range");
            c = (HttpURLConnection) new URL(entry.base + "/download?path=" + Util.enc(entry.path)).openConnection();
            registerConnection(c);
            App.auth(c, context);
            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
            c.setConnectTimeout(10000);
            c.setReadTimeout(40000);
            int code = c.getResponseCode();
            if (code != 206) throw new RuntimeException("HTTP " + code + " for range " + start + "-" + end);
            in = c.getInputStream();
            out = new RandomAccessFile(entry.partFile, "rw");
            out.seek(start);
            byte[] buf = new byte[BUFFER_SIZE];
            long pos = start;
            int read;
            while (!cancelled && pos <= end && (read = in.read(buf, 0, (int) Math.min(buf.length, end - pos + 1))) != -1) {
                out.write(buf, 0, read);
                pos += read;
            }
            if (cancelled) return;
            if (pos <= end) throw new RuntimeException("short range " + start + "-" + end + " got=" + (pos - start));
            markChunkComplete(idx);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
            if (c != null) {
                unregisterConnection(c);
                c.disconnect();
            }
            if (l != null) l.close();
        }
    }

    private void prepareChunkTable(long total) {
        long chunkSize = chooseChunkSize(total);
        int count = (int) ((total + chunkSize - 1L) / chunkSize);
        if (count < 1) count = 1;
        chunkDone = new boolean[count];
        chunkStart = new long[count];
        chunkEnd = new long[count];
        for (int i = 0; i < count; i++) {
            long s = i * chunkSize;
            long e = Math.min(total - 1L, s + chunkSize - 1L);
            chunkStart[i] = s;
            chunkEnd[i] = e;
        }
        contiguousChunkIndex = 0;
    }

    private static long chooseChunkSize(long total) {
        if (total <= 256L * MB) return 4L * MB;
        if (total <= 1024L * MB) return 8L * MB;
        if (total <= 4L * 1024L * MB) return 16L * MB;
        return 32L * MB;
    }

    private void markChunkComplete(int idx) {
        synchronized (lock) {
            if (chunkDone == null || idx < 0 || idx >= chunkDone.length || chunkDone[idx]) return;
            chunkDone[idx] = true;
            long cached = entry.cachedBytes + (chunkEnd[idx] - chunkStart[idx] + 1L);
            if (cached > entry.totalBytes && entry.totalBytes > 0) cached = entry.totalBytes;
            entry.cachedBytes = cached;
            while (contiguousChunkIndex < chunkDone.length && chunkDone[contiguousChunkIndex]) {
                entry.downloadedBytes = chunkEnd[contiguousChunkIndex] + 1L;
                contiguousChunkIndex++;
            }
            if (entry.totalBytes > 0 && entry.downloadedBytes > entry.totalBytes) entry.downloadedBytes = entry.totalBytes;
            addSampleLocked(entry.cachedBytes);
            lock.notifyAll();
        }
        maybeNotifyProgress();
        if (!prefetch) {
            maybeNotifyReady();
            maybeTimeoutFallback();
        }
    }

    private boolean allChunksComplete() {
        synchronized (lock) {
            if (chunkDone == null) return false;
            for (boolean b : chunkDone) if (!b) return false;
            return true;
        }
    }

    private void completeFile(long total) {
        complete = true;
        PlayerDiagnostics.log(context, prefetch ? "prefetch" : "cache", "complete total=" + total + " path=" + entry.path);
        synchronized (lock) {
            entry.cachedBytes = total;
            entry.downloadedBytes = total;
            entry.totalBytes = total;
            addSampleLocked(total);
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
        long available = downloadedBytes();
        long target = prepareTargetBytes();
        long elapsed = System.currentTimeMillis() - startAtMs;
        boolean targetReached = target > 0 && available >= target;
        boolean early = available >= MIN_EARLY_START_BYTES
                && elapsed >= 5000L
                && recentSpeedBytesPerSec() >= MIN_EARLY_SPEED_BYTES_PER_SEC;
        boolean timeoutButUsable = elapsed >= PREPARE_TIMEOUT_MS
                && available >= MIN_EARLY_START_BYTES
                && recentSpeedBytesPerSec() >= 1024L * 1024L;
        if (targetReached || early || timeoutButUsable) {
            readyNotified = true;
            PlayerDiagnostics.log(context, "cache", "ready early=" + (!targetReached) + " available=" + available + " cached=" + cachedBytes() + " target=" + target + " speed=" + recentSpeedBytesPerSec());
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
        PlayerDiagnostics.log(context, "cache-fallback", reason + " available=" + downloadedBytes() + " cached=" + cachedBytes() + " target=" + prepareTargetBytes() + " speed=" + recentSpeedBytesPerSec());
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
        disconnectAll();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void registerConnection(HttpURLConnection c) {
        synchronized (activeConnections) {
            activeConnections.add(c);
        }
    }

    private void unregisterConnection(HttpURLConnection c) {
        synchronized (activeConnections) {
            activeConnections.remove(c);
        }
    }

    private void disconnectAll() {
        ArrayList<HttpURLConnection> copy;
        synchronized (activeConnections) {
            copy = new ArrayList<>(activeConnections);
            activeConnections.clear();
        }
        for (HttpURLConnection c : copy) {
            try { c.disconnect(); } catch (Throwable ignored) {}
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
