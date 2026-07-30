package com.tabletplayer;

import android.content.Context;
import android.os.Process;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Последовательно предзагружает до трёх следующих элементов выбранной очереди. */
final class QueuePrefetcher {
    interface Listener {
        void onPrefetchChanged(String path, long downloaded, long total, boolean complete);
    }

    private static final int AHEAD_COUNT = 3;
    private static final int BUFFER_SIZE = 512 * 1024;
    private static final long STORAGE_RESERVE = 128L * 1024L * 1024L;

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "queue-prefetch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Future<?> future;
    private volatile HttpURLConnection connection;
    private volatile boolean closed;

    QueuePrefetcher(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    synchronized void pauseFor(String path) {
        cancelCurrent();
    }

    synchronized void updateWindow(String base, List<String> paths, Map<String, Long> sizes,
                                   int currentIndex) {
        if (closed || base == null || paths == null || currentIndex < 0) return;
        cancelCurrent();
        final int gen = generation.get();
        final List<String> snapshot = new ArrayList<>(paths);
        final Map<String, Long> sizeSnapshot = new HashMap<>(sizes);
        final int index = currentIndex;
        cleanupWindow(base, snapshot, index);
        future = executor.submit(() -> runWindow(gen, base, snapshot, sizeSnapshot, index));
    }

    private void runWindow(int gen, String base, List<String> paths, Map<String, Long> sizes,
                           int currentIndex) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
        int end = Math.min(paths.size(), currentIndex + 1 + AHEAD_COUNT);
        for (int i = currentIndex + 1; i < end; i++) {
            if (closed || gen != generation.get() || Thread.currentThread().isInterrupted()) return;
            String path = paths.get(i);
            Long size = sizes.get(path);
            if (size == null || size <= 0) continue;
            try {
                synchronized (CacheFiles.lock(base, path)) {
                    if (closed || gen != generation.get()) return;
                    prefetchOne(gen, base, path, size);
                }
            } catch (Throwable ignored) {
                if (closed || gen != generation.get()) return;
            }
        }
    }

    private void prefetchOne(int gen, String base, String path, long total) throws Exception {
        File file = CacheFiles.file(context, base, path);
        long existing = file.exists() ? file.length() : 0L;
        if (existing == total) {
            PlaybackDiagnostics.log(context, "prefetch ready path=" + path);
            notifyChanged(path, existing, total, true);
            return;
        }
        if (existing > total) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            existing = 0L;
        }
        long usable = file.getParentFile().getUsableSpace();
        if (usable > 0 && total - existing > Math.max(0L, usable - STORAGE_RESERVE)) return;

        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        TransferCoordinator.Lease transferLease = null;
        try {
            transferLease = TransferCoordinator.acquire(TransferCoordinator.PREFETCH);
            if (closed || gen != generation.get() || Thread.currentThread().isInterrupted()) return;
            c = open(base, path, existing);
            int code = c.getResponseCode();
            if (existing > 0L && (code == 416 || (code == 206 && !validRange(c, existing)))) {
                PlaybackDiagnostics.log(context, "prefetch resume rejected path=" + path
                        + " code=" + code + " existing=" + existing);
                c.disconnect();
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                existing = 0L;
                c = open(base, path, 0L);
                code = c.getResponseCode();
            }
            connection = c;
            if (code != 200 && code != 206) throw new IllegalStateException("HTTP " + code);
            App.markPaired(context, c);
            boolean append = code == 206 && existing > 0 && validRange(c, existing);
            if (!append) existing = 0L;
            in = new BufferedInputStream(c.getInputStream(), BUFFER_SIZE);
            out = new FileOutputStream(file, append);
            long done = existing;
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            long lastNotify = 0L;
            while (gen == generation.get() && !closed && !Thread.currentThread().isInterrupted()
                    && (read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                done += read;
                long now = System.currentTimeMillis();
                if (now - lastNotify >= 1000L) {
                    lastNotify = now;
                    notifyChanged(path, done, total, false);
                }
            }
            out.flush();
            if (gen == generation.get() && !closed && done == total) {
                file.setLastModified(System.currentTimeMillis());
                PlaybackDiagnostics.log(context, "prefetch complete path=" + path + " bytes=" + done);
                notifyChanged(path, done, total, true);
            }
        } finally {
            connection = null;
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
            if (transferLease != null) transferLease.release();
        }
    }

    private HttpURLConnection open(String base, String path, long existing) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/download?path=" + Util.enc(path)).openConnection();
            App.auth(c, context);
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
            int code = c.getResponseCode();
            if (code == 403 && attempt == 0 && App.retryPairingAfterForbidden(context, c)) {
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new IllegalStateException("HTTP 403");
    }

    private static boolean validRange(HttpURLConnection c, long start) {
        String value = c.getHeaderField("Content-Range");
        if (value == null) return false;
        value = value.trim().toLowerCase(java.util.Locale.US);
        return value.startsWith("bytes " + start + "-");
    }

    private void notifyChanged(String path, long downloaded, long total, boolean complete) {
        if (listener != null) listener.onPrefetchChanged(path, downloaded, total, complete);
    }

    private void cleanupWindow(String base, List<String> paths, int currentIndex) {
        Set<File> keep = new HashSet<>();
        int end = Math.min(paths.size(), currentIndex + 1 + AHEAD_COUNT);
        for (int i = currentIndex; i < end; i++) keep.add(CacheFiles.file(context, base, paths.get(i)));
        CacheFiles.cleanup(context, keep, false);
    }

    private synchronized void cancelCurrent() {
        generation.incrementAndGet();
        HttpURLConnection c = connection;
        if (c != null) c.disconnect();
        Future<?> f = future;
        if (f != null) {
            f.cancel(true);
            try { f.get(1200L, TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
        }
        future = null;
    }

    synchronized void closeAndClear() {
        if (closed) return;
        closed = true;
        cancelCurrent();
        executor.shutdownNow();
        try { executor.awaitTermination(1500L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        CacheFiles.cleanup(context, null, false);
    }
}
