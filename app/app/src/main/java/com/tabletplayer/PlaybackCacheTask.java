package com.tabletplayer;

import android.content.Context;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Оконный Range-загрузчик для локального playback-cache.
 *
 * Он не раскладывает весь файл в очередь сразу:
 * - до запуска планирует только подготовочную область 0..30%/300 МБ;
 * - после запуска держит скользящее окно вперёд;
 * - после seek в незагруженную область создаёт срочное окно от новой позиции.
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
    private static final long PREPARE_SOFT_TIMEOUT_MS = 30000L;
    private static final long PREPARE_HARD_TIMEOUT_MS = 120000L;
    private static final long SPEED_SAMPLE_WINDOW_MS = 8000L;
    private static final long MIN_EARLY_SPEED_BYTES_PER_SEC = 1536L * 1024L;
    private static final long MIN_KEEP_WAIT_SPEED_BYTES_PER_SEC = 512L * 1024L;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int MAX_CHUNK_RETRIES = 3;
    private static final long RAM_BUFFER_MIN_BYTES = 16L * MB;
    private static final long RAM_BUFFER_MAX_BYTES = 48L * MB;
    private static final long UI_PROGRESS_INTERVAL_MS = 800L;

    private static final int CHUNK_PENDING = 0;
    private static final int CHUNK_IN_FLIGHT = 1;
    private static final int CHUNK_DONE = 2;

    private final Context context;
    private final PlaybackCacheManager.Entry entry;
    private final Listener listener;
    private final boolean prefetch;
    private final Object lock = new Object();
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final List<HttpURLConnection> activeConnections = new ArrayList<>();
    private final ArrayList<Chunk> chunks = new ArrayList<>();
    private final ArrayDeque<Chunk> pendingChunks = new ArrayDeque<>();
    private final Object writeLock = new Object();
    private final ArrayDeque<WriteBlock> writeQueue = new ArrayDeque<>();


    private volatile boolean cancelled;
    private volatile boolean complete;
    private volatile boolean finished;
    private volatile boolean readyNotified;
    private volatile boolean fallbackNotified;
    private volatile Exception error;
    private volatile Thread thread;

    private long startAtMs;
    private long lastProgressNotifyMs;
    private long chunkSize;
    private long scheduledUntilBytes;
    private long seekFocusStartBytes = -1;
    private long seekFocusScheduledUntilBytes = -1;
    private long networkReceivedBytes;
    private long diskWrittenBytes;
    private long queuedWriteBytes;
    private long maxRamBufferBytes;
    private long lastSpeedUpdateMs;
    private long lastSpeedBytes;
    private double smoothedSpeedBytesPerSec;
    private volatile boolean writerStopRequested;


    public PlaybackCacheTask(Context context, PlaybackCacheManager.Entry entry, boolean prefetch, Listener listener) {
        this.context = context.getApplicationContext();
        this.entry = entry;
        this.prefetch = prefetch;
        this.listener = listener;
    }

    public PlaybackCacheManager.Entry entry() {
        return entry;
    }

    /** Непрерывно доступно от начала файла. Для обычного старта и secondaryProgress. */
    public long downloadedBytes() {
        synchronized (lock) {
            return entry.downloadedBytes;
        }
    }

    /** Получено из сети суммарно, включая окна после seek. Может быть больше downloadedBytes(). */
    public long cachedBytes() {
        synchronized (lock) {
            return Math.max(Math.max(entry.cachedBytes, entry.downloadedBytes), networkReceivedBytes);
        }
    }

    /** Уже записано на диск. Range для proxy считается готовым только после записи. */
    public long diskWrittenBytes() {
        synchronized (lock) {
            return diskWrittenBytes;
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
        long total = totalBytes();
        int cap = prefetch ? Store.getPlaybackPrefetchThreads(context) : Store.getPlaybackCacheThreads(context);
        int auto = prefetch ? Math.min(cap, 3) : autoWorkerCount(total);
        if (!prefetch) return Math.max(3, Math.min(cap, auto));
        return Math.max(1, Math.min(cap, auto));
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
        long start = bytesForTime(ms, durationMs);
        long end = Math.min(total - 1L, start + Math.max(1L, extraBytes) - 1L);
        return isRangeAvailable(start, end);
    }

    /** Срочно планирует окно загрузки от позиции перемотки, не добивая путь до неё. */
    public void requestSeekWindow(long startByte, long minAheadBytes) {
        long total = totalBytes();
        if (total <= 0) return;
        long s = Math.max(0, Math.min(total - 1L, startByte));
        long ahead = Math.max(Math.max(minAheadBytes, seekInitialWindowBytes(total)), chunkSizeFor(total));
        synchronized (lock) {
            seekFocusStartBytes = s;
            seekFocusScheduledUntilBytes = s;
            long urgentEnd = Math.min(total, s + ahead);
            if (s > entry.downloadedBytes + playbackWindowBytes(total) / 2L) {
                dropPendingChunksOutsideLocked(s, urgentEnd);
            }
            scheduleRangeLocked(s, urgentEnd, true);
            lock.notifyAll();
        }
        PlayerDiagnostics.log(context, "cache-seek-window", "start=" + s + " ahead=" + ahead + " total=" + total);
        maybeNotifyProgress();
    }

    public long recentSpeedBytesPerSec() {
        synchronized (lock) {
            if (smoothedSpeedBytesPerSec > 0) return Math.max(0L, (long) smoothedSpeedBytesPerSec);
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
                chunks.clear();
                pendingChunks.clear();
                chunkSize = chunkSizeFor(total);
                scheduledUntilBytes = 0;
                seekFocusStartBytes = -1;
                seekFocusScheduledUntilBytes = -1;
                networkReceivedBytes = 0;
                diskWrittenBytes = 0;
                queuedWriteBytes = 0;
                maxRamBufferBytes = maxRamBufferBytesFor(total);
                lastSpeedUpdateMs = 0;
                lastSpeedBytes = 0;
                smoothedSpeedBytesPerSec = 0;
                addSampleLocked(0);
            }
            PlayerDiagnostics.log(context, prefetch ? "prefetch" : "cache", "total=" + total + " target=" + prepareTargetBytes() + " chunk=" + chunkSizeFor(total) + " workers=" + workerCount() + " ram=" + maxRamBufferBytesFor(total) + " max=" + TransferCoordinator.get().maxRemoteTransfers());
            downloadWindowed(total);
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

    private void downloadWindowed(long total) throws Exception {
        if (entry.partFile.exists()) entry.partFile.delete();
        RandomAccessFile init = new RandomAccessFile(entry.partFile, "rw");
        try {
            init.setLength(total);
        } finally {
            init.close();
        }

        synchronized (lock) {
            if (prefetch) {
                scheduleRangeLocked(0, total, false);
            } else {
                scheduleRangeLocked(0, Math.min(total, prepareTargetBytes()), false);
            }
        }

        writerStopRequested = false;
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                runDiskWriter();
            }
        }, prefetch ? "PrefetchDiskWriter" : "CacheDiskWriter");
        writer.setDaemon(true);
        writer.setPriority(prefetch ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY);
        writer.start();

        int workers = Math.max(1, workerCount());
        ArrayList<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            final int workerIndex = i;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    runWorker(total, workerIndex);
                }
            }, (prefetch ? "PrefetchRange-" : "CacheRange-") + i);
            t.setDaemon(true);
            t.setPriority(prefetch ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY);
            threads.add(t);
            t.start();
        }

        boolean anyAlive;
        do {
            anyAlive = false;
            for (Thread t : threads) {
                if (!t.isAlive()) continue;
                anyAlive = true;
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
        } while (anyAlive && !cancelled && error == null);

        requestWriterStop();
        try {
            writer.join(30000L);
        } catch (InterruptedException e) {
            cancelled = true;
            Thread.currentThread().interrupt();
        }

        if (error != null) throw error;
        if (!cancelled && entry.downloadedBytes >= total) completeFile(total);
    }

    private void runWorker(long total, int workerIndex) {
        while (!cancelled && error == null) {
            Chunk chunk = takeNextChunk(total);
            if (chunk == null) return;
            boolean ok = false;
            Exception last = null;
            for (int attempt = 1; attempt <= MAX_CHUNK_RETRIES && !cancelled; attempt++) {
                try {
                    downloadChunk(chunk);
                    ok = true;
                    break;
                } catch (Exception e) {
                    last = e;
                    PlayerDiagnostics.log(context, prefetch ? "prefetch-retry" : "cache-retry", "worker=" + workerIndex + " range=" + chunk.start + "-" + chunk.end + " attempt=" + attempt + " err=" + e.getMessage());
                    try { Thread.sleep(200L * attempt); } catch (InterruptedException ignored) { break; }
                }
            }
            if (!ok && !cancelled) {
                synchronized (lock) {
                    if (chunk.state != CHUNK_DONE) chunk.state = CHUNK_PENDING;
                    pendingChunks.addFirst(chunk);
                    lock.notifyAll();
                }
                error = last == null ? new RuntimeException("chunk failed") : last;
                cancelled = true;
                disconnectAll();
                synchronized (lock) {
                    lock.notifyAll();
                }
                return;
            }
        }
    }

    private Chunk takeNextChunk(long total) {
        synchronized (lock) {
            while (!cancelled && error == null) {
                maybeScheduleWindowLocked(total);
                Chunk c = pendingChunks.pollFirst();
                if (c != null) {
                    if (c.state == CHUNK_PENDING) {
                        c.state = CHUNK_IN_FLIGHT;
                        return c;
                    }
                    continue;
                }
                if (entry.downloadedBytes >= total) return null;
                if (prefetch && scheduledUntilBytes >= total && !hasInFlightLocked() && !hasPendingLocked()) return null;
                try {
                    lock.wait(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }
    }

    private void downloadChunk(Chunk chunk) throws Exception {
        TransferCoordinator.Lease l = null;
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            l = TransferCoordinator.get().acquire(prefetch ? TransferCoordinator.Priority.PREFETCH : TransferCoordinator.Priority.PLAYBACK_CACHE, prefetch ? "prefetch-range" : "cache-range");
            c = (HttpURLConnection) new URL(entry.base + "/download?path=" + Util.enc(entry.path)).openConnection();
            registerConnection(c);
            App.auth(c, context);
            c.setRequestProperty("Range", "bytes=" + chunk.start + "-" + chunk.end);
            c.setConnectTimeout(10000);
            c.setReadTimeout(40000);
            int code = c.getResponseCode();
            if (code != 206) throw new RuntimeException("HTTP " + code + " for range " + chunk.start + "-" + chunk.end);
            in = c.getInputStream();
            byte[] buf = new byte[BUFFER_SIZE];
            long pos = chunk.start;
            int read;
            while (!cancelled && error == null && pos <= chunk.end && (read = in.read(buf, 0, (int) Math.min(buf.length, chunk.end - pos + 1))) != -1) {
                byte[] data = Arrays.copyOf(buf, read);
                enqueueWriteBlock(new WriteBlock(chunk, pos, data, read, false));
                pos += read;
                onNetworkBytes(chunk, pos - chunk.start);
            }
            if (cancelled || error != null) return;
            if (pos <= chunk.end) throw new RuntimeException("short range " + chunk.start + "-" + chunk.end + " got=" + (pos - chunk.start));
            enqueueWriteBlock(new WriteBlock(chunk, 0, null, 0, true));
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (c != null) {
                unregisterConnection(c);
                c.disconnect();
            }
            if (l != null) l.close();
        }
    }

    private void runDiskWriter() {
        RandomAccessFile out = null;
        try {
            out = new RandomAccessFile(entry.partFile, "rw");
            while (!cancelled || hasQueuedWriteBlocks() || !writerStopRequested) {
                WriteBlock block = takeWriteBlock();
                if (block == null) {
                    if (writerStopRequested || cancelled || error != null) break;
                    continue;
                }
                if (block.completeMarker) {
                    markChunkComplete(block.chunk);
                    continue;
                }
                out.seek(block.position);
                out.write(block.data, 0, block.length);
                onDiskBytes(block.chunk, block.position + block.length - block.chunk.start);
            }
        } catch (Exception e) {
            error = e;
            cancelled = true;
            PlayerDiagnostics.log(context, prefetch ? "prefetch-writer-error" : "cache-writer-error", e);
            disconnectAll();
            synchronized (lock) { lock.notifyAll(); }
            synchronized (writeLock) { writeLock.notifyAll(); }
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    private void enqueueWriteBlock(WriteBlock block) throws InterruptedException {
        synchronized (writeLock) {
            while (!cancelled && error == null && block.length > 0 && queuedWriteBytes + block.length > maxRamBufferBytes) {
                writeLock.wait(250L);
            }
            if (cancelled || error != null) throw new InterruptedException("cache writer stopped");
            writeQueue.addLast(block);
            queuedWriteBytes += block.length;
            writeLock.notifyAll();
        }
    }

    private WriteBlock takeWriteBlock() throws InterruptedException {
        synchronized (writeLock) {
            while (!writerStopRequested && !cancelled && error == null && writeQueue.isEmpty()) {
                writeLock.wait(250L);
            }
            WriteBlock block = writeQueue.pollFirst();
            if (block != null && block.length > 0) {
                queuedWriteBytes -= block.length;
                if (queuedWriteBytes < 0) queuedWriteBytes = 0;
                writeLock.notifyAll();
            }
            return block;
        }
    }

    private boolean hasQueuedWriteBlocks() {
        synchronized (writeLock) {
            return !writeQueue.isEmpty();
        }
    }

    private void requestWriterStop() {
        writerStopRequested = true;
        synchronized (writeLock) {
            writeLock.notifyAll();
        }
    }

    private void onNetworkBytes(Chunk chunk, long receivedInChunk) {
        if (chunk == null || receivedInChunk <= 0) return;
        synchronized (lock) {
            long clamped = Math.max(0L, Math.min(chunk.length(), receivedInChunk));
            long delta = clamped - chunk.receivedBytes;
            if (delta <= 0) return;
            chunk.receivedBytes = clamped;
            long total = entry.totalBytes;
            networkReceivedBytes += delta;
            if (total > 0 && networkReceivedBytes > total) networkReceivedBytes = total;
            if (networkReceivedBytes > entry.cachedBytes) entry.cachedBytes = networkReceivedBytes;
            updateSmoothedSpeedLocked();
            addSampleLocked(entry.cachedBytes);
            lock.notifyAll();
        }
        maybeNotifyProgress();
    }

    private void onDiskBytes(Chunk chunk, long writtenInChunk) {
        if (chunk == null || writtenInChunk <= 0) return;
        synchronized (lock) {
            long clamped = Math.max(0L, Math.min(chunk.length(), writtenInChunk));
            long delta = clamped - chunk.writtenBytes;
            if (delta <= 0) return;
            chunk.writtenBytes = clamped;
            diskWrittenBytes += delta;
            long total = entry.totalBytes;
            if (total > 0 && diskWrittenBytes > total) diskWrittenBytes = total;
            lock.notifyAll();
        }
    }

    private void updateSmoothedSpeedLocked() {
        long now = System.currentTimeMillis();
        if (lastSpeedUpdateMs <= 0) {
            lastSpeedUpdateMs = now;
            lastSpeedBytes = networkReceivedBytes;
            return;
        }
        long dt = now - lastSpeedUpdateMs;
        if (dt < 300L) return;
        long db = networkReceivedBytes - lastSpeedBytes;
        long instant = dt > 0 ? Math.max(0L, db * 1000L / dt) : 0L;
        if (smoothedSpeedBytesPerSec <= 0) smoothedSpeedBytesPerSec = instant;
        else smoothedSpeedBytesPerSec = smoothedSpeedBytesPerSec * 0.75d + instant * 0.25d;
        lastSpeedUpdateMs = now;
        lastSpeedBytes = networkReceivedBytes;
    }

    private void scheduleRangeLocked(long start, long endExclusive, boolean priority) {
        long total = entry.totalBytes;
        if (total <= 0 || cancelled) return;
        long size = chunkSize > 0 ? chunkSize : chunkSizeFor(total);
        long s = Math.max(0, Math.min(total, start));
        long end = Math.max(s, Math.min(total, endExclusive));
        if (end <= s) return;
        long aligned = (s / size) * size;
        for (long p = aligned; p < end; p += size) {
            long e = Math.min(total - 1L, p + size - 1L);
            Chunk existing = findChunkByStartLocked(p);
            if (existing != null) continue;
            Chunk c = new Chunk(p, e);
            chunks.add(c);
            if (priority) pendingChunks.addFirst(c);
            else pendingChunks.addLast(c);
        }
        if (!priority && end > scheduledUntilBytes) scheduledUntilBytes = end;
        if (priority && end > seekFocusScheduledUntilBytes) seekFocusScheduledUntilBytes = end;
        lock.notifyAll();
    }


    private void dropPendingChunksOutsideLocked(long start, long endExclusive) {
        java.util.Iterator<Chunk> pit = pendingChunks.iterator();
        while (pit.hasNext()) {
            Chunk c = pit.next();
            if (c.state != CHUNK_PENDING) continue;
            if (c.end < start || c.start >= endExclusive) pit.remove();
        }
        for (int i = chunks.size() - 1; i >= 0; i--) {
            Chunk c = chunks.get(i);
            if (c.state != CHUNK_PENDING) continue;
            if (c.end < start || c.start >= endExclusive) chunks.remove(i);
        }
    }

    private void maybeScheduleWindowLocked(long total) {
        if (total <= 0 || cancelled) return;
        if (prefetch) {
            if (scheduledUntilBytes < total) scheduleRangeLocked(scheduledUntilBytes, total, false);
            return;
        }
        long prepareEnd = Math.min(total, prepareTargetBytes());
        if (!readyNotified) {
            if (scheduledUntilBytes < prepareEnd) scheduleRangeLocked(scheduledUntilBytes, prepareEnd, false);
            return;
        }

        long window = playbackWindowBytes(total);
        if (seekFocusStartBytes >= 0) {
            long focusEnd = contiguousEndFromLocked(seekFocusStartBytes);
            long base = Math.max(Math.max(focusEnd, seekFocusScheduledUntilBytes), seekFocusStartBytes);
            long wanted = Math.min(total, base + window);
            if (seekFocusScheduledUntilBytes < wanted) {
                scheduleRangeLocked(seekFocusScheduledUntilBytes, wanted, true);
            }
            // После дальнего seek не продолжаем грузить старый путь до новой позиции.
            if (seekFocusStartBytes > entry.downloadedBytes + window) return;
        }

        long base = Math.max(entry.downloadedBytes, scheduledUntilBytes);
        long wanted = Math.min(total, base + window);
        if (scheduledUntilBytes < wanted) scheduleRangeLocked(scheduledUntilBytes, wanted, false);
    }

    private void markChunkComplete(Chunk chunk) {
        synchronized (lock) {
            if (chunk.state == CHUNK_DONE) return;
            chunk.state = CHUNK_DONE;
            entry.downloadedBytes = contiguousEndFromLocked(0);
            if (entry.totalBytes > 0 && entry.downloadedBytes > entry.totalBytes) entry.downloadedBytes = entry.totalBytes;
            if (diskWrittenBytes > entry.cachedBytes) entry.cachedBytes = diskWrittenBytes;
            maybeScheduleWindowLocked(entry.totalBytes);
            lock.notifyAll();
        }
        maybeNotifyProgress();
        if (!prefetch) {
            maybeNotifyReady();
            maybeTimeoutFallback();
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
        long speed = recentSpeedBytesPerSec();
        boolean targetReached = target > 0 && available >= target;
        boolean early = available >= MIN_EARLY_START_BYTES
                && elapsed >= 5000L
                && speed >= MIN_EARLY_SPEED_BYTES_PER_SEC;
        boolean timeoutButUsable = elapsed >= PREPARE_SOFT_TIMEOUT_MS
                && available >= MIN_EARLY_START_BYTES
                && speed >= MIN_KEEP_WAIT_SPEED_BYTES_PER_SEC;
        if (targetReached || early || timeoutButUsable) {
            readyNotified = true;
            PlayerDiagnostics.log(context, "cache", "ready early=" + (!targetReached) + " available=" + available + " cached=" + cachedBytes() + " target=" + target + " speed=" + speed);
            PlaybackCacheManager.get().markState(entry, PlaybackCacheManager.State.PLAYING);
            synchronized (lock) {
                maybeScheduleWindowLocked(entry.totalBytes);
                lock.notifyAll();
            }
            if (listener != null) listener.onCacheReady(this, !targetReached);
        }
    }

    private void maybeTimeoutFallback() {
        if (readyNotified || fallbackNotified || cancelled) return;
        long elapsed = System.currentTimeMillis() - startAtMs;
        if (elapsed < PREPARE_SOFT_TIMEOUT_MS) return;
        long speed = recentSpeedBytesPerSec();
        long available = downloadedBytes();
        long cached = cachedBytes();
        if (speed >= MIN_KEEP_WAIT_SPEED_BYTES_PER_SEC && cached > 0) return;
        if (elapsed < PREPARE_HARD_TIMEOUT_MS && (available >= 8L * MB || cached >= 16L * MB)) return;
        notifyFallback("подготовка не продвигается: скорость низкая или нет непрерывного кэша");
    }

    private void notifyFallback(String reason) {
        if (fallbackNotified || readyNotified || cancelled || prefetch) return;
        fallbackNotified = true;
        PlayerDiagnostics.log(context, "cache-fallback", reason + " available=" + downloadedBytes() + " cached=" + cachedBytes() + " target=" + prepareTargetBytes() + " speed=" + recentSpeedBytesPerSec());
        if (listener != null) listener.onCacheFallback(this, reason);
    }

    private void maybeNotifyProgress() {
        long now = System.currentTimeMillis();
        if (now - lastProgressNotifyMs < UI_PROGRESS_INTERVAL_MS) return;
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
        if (bytes <= 0) return true;
        return waitForRange(0, bytes - 1L, timeoutMs);
    }

    @Override
    public boolean waitForRange(long start, long endInclusive, long timeoutMs) throws InterruptedException {
        long total = totalBytes();
        if (total <= 0) return false;
        long s = Math.max(0, Math.min(total - 1L, start));
        long e = Math.max(s, Math.min(total - 1L, endInclusive));
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            scheduleRangeLocked(s, Math.min(total, e + 1L), s > entry.downloadedBytes + playbackWindowBytes(total) / 2L);
            while (!cancelled && error == null && !isRangeAvailableLocked(s, e) && !complete) {
                maybeScheduleWindowLocked(total);
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                lock.wait(Math.min(left, 1000L));
            }
            return isRangeAvailableLocked(s, e) || complete;
        }
    }

    public boolean isRangeAvailable(long start, long endInclusive) {
        synchronized (lock) {
            return isRangeAvailableLocked(start, endInclusive);
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
        requestWriterStop();
        disconnectAll();
        synchronized (lock) {
            lock.notifyAll();
        }
        synchronized (writeLock) {
            writeLock.notifyAll();
        }
    }

    private boolean isRangeAvailableLocked(long start, long endInclusive) {
        if (entry.finalFile.exists() || complete) return true;
        long total = entry.totalBytes;
        if (total <= 0) return false;
        long s = Math.max(0, Math.min(total - 1L, start));
        long e = Math.max(s, Math.min(total - 1L, endInclusive));
        if (s == 0 && entry.downloadedBytes >= e + 1L) return true;
        long pos = s;
        while (pos <= e) {
            Chunk c = findDoneChunkContainingLocked(pos);
            if (c == null) return false;
            if (c.end >= e) return true;
            pos = c.end + 1L;
        }
        return true;
    }

    private long contiguousEndFromLocked(long start) {
        long total = entry.totalBytes;
        if (total <= 0) return 0;
        long pos = Math.max(0, Math.min(total, start));
        while (pos < total) {
            Chunk c = findDoneChunkContainingLocked(pos);
            if (c == null) break;
            pos = Math.min(total, c.end + 1L);
        }
        return pos;
    }

    private Chunk findDoneChunkContainingLocked(long pos) {
        for (Chunk c : chunks) {
            if (c.state == CHUNK_DONE && c.start <= pos && c.end >= pos) return c;
        }
        return null;
    }

    private Chunk findChunkByStartLocked(long start) {
        for (Chunk c : chunks) {
            if (c.start == start) return c;
        }
        return null;
    }

    private boolean hasPendingLocked() {
        for (Chunk c : chunks) if (c.state == CHUNK_PENDING) return true;
        return false;
    }

    private boolean hasInFlightLocked() {
        for (Chunk c : chunks) if (c.state == CHUNK_IN_FLIGHT) return true;
        return false;
    }

    private static long maxRamBufferBytesFor(long total) {
        if (total <= 0) return 32L * MB;
        if (total <= 256L * MB) return 24L * MB;
        if (total <= 1024L * MB) return 32L * MB;
        return RAM_BUFFER_MAX_BYTES;
    }

    private static int autoWorkerCount(long total) {
        if (total <= 0) return 8;
        if (total <= 256L * MB) return 12;
        if (total <= 1024L * MB) return 10;
        if (total <= 4L * 1024L * MB) return 8;
        if (total <= 8L * 1024L * MB) return 5;
        return 3;
    }

    private static long chunkSizeFor(long total) {
        if (total <= 256L * MB) return 2L * MB;
        if (total <= 1024L * MB) return 6L * MB;
        if (total <= 4L * 1024L * MB) return 12L * MB;
        if (total <= 8L * 1024L * MB) return 24L * MB;
        return 32L * MB;
    }

    private long playbackWindowBytes(long total) {
        long cs = chunkSizeFor(total);
        int wc = autoWorkerCount(total);
        long byWorkers = cs * Math.max(3, wc) * 4L;
        long floor;
        if (total <= 256L * MB) floor = 32L * MB;
        else if (total <= 1024L * MB) floor = 96L * MB;
        else if (total <= 4L * 1024L * MB) floor = 192L * MB;
        else floor = 256L * MB;
        return Math.min(total, Math.max(floor, byWorkers));
    }

    private static long seekInitialWindowBytes(long total) {
        if (total <= 256L * MB) return 8L * MB;
        if (total <= 1024L * MB) return 12L * MB;
        if (total <= 4L * 1024L * MB) return 24L * MB;
        return 32L * MB;
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

    private static final class Chunk {
        final long start;
        final long end;
        long receivedBytes;
        long writtenBytes;
        int state = CHUNK_PENDING;

        Chunk(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long length() {
            return end - start + 1L;
        }
    }

    private static final class WriteBlock {
        final Chunk chunk;
        final long position;
        final byte[] data;
        final int length;
        final boolean completeMarker;

        WriteBlock(Chunk chunk, long position, byte[] data, int length, boolean completeMarker) {
            this.chunk = chunk;
            this.position = position;
            this.data = data;
            this.length = length;
            this.completeMarker = completeMarker;
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
