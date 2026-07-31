package com.tabletplayer;

import android.content.Context;
import android.os.Process;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Последовательно загружает текущую серию в устойчивый дисковый кэш и отдаёт
 * доступные байты libVLC через 127.0.0.1. Один основной удалённый запрос
 * используется на весь файл; небольшие хвостовые Range допускаются только для
 * индекса контейнера.
 */
final class PlaybackCache {
    interface Listener {
        void onCacheChanged();
    }

    private static final long MAX_PREPARE_BYTES = 300L * 1024L * 1024L;
    private static final long MIN_DYNAMIC_BYTES = 64L * 1024L * 1024L;
    private static final long USER_SEEK_AHEAD_BYTES = 32L * 1024L * 1024L;
    private static final long DECODER_AHEAD_BYTES = 8L * 1024L * 1024L;
    private static final long METADATA_TAIL_WINDOW_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_METADATA_RANGE_BYTES = 24L * 1024L * 1024L;
    private static final long STORAGE_RESERVE_BYTES = 128L * 1024L * 1024L;
    private static final long UNKNOWN_DURATION_ASSUMED_MS = 20L * 60L * 1000L;
    // Умеренные буферы уменьшают число Java-чтений и системных вызовов, но
    // остаются достаточно небольшими для старого Android даже при нескольких
    // служебных Range-подключениях libVLC.
    private static final int DOWNLOAD_BUFFER_SIZE = 256 * 1024;
    private static final int STREAM_BUFFER_SIZE = 256 * 1024;
    private static final int SOCKET_SEND_BUFFER_SIZE = 512 * 1024;
    private static final long FILE_TOUCH_INTERVAL_MS = 5000L;

    private final Context context;
    private final Listener listener;
    private final Object dataLock = new Object();
    private final Object stopLock = new Object();
    private final AtomicInteger waitingClients = new AtomicInteger();
    private final AtomicInteger metadataRequests = new AtomicInteger();
    private final AtomicLong waitingOffset = new AtomicLong(-1L);
    private final AtomicLong userSeekByte = new AtomicLong(-1L);

    private File cacheFile;
    private volatile boolean cancelled;
    private volatile boolean deleteWhenStopped;
    private volatile boolean playbackEstablished;
    private volatile boolean complete;
    private volatile boolean failed;
    private volatile String error = "";
    private volatile long totalBytes = -1L;
    private volatile long downloadedBytes;
    private volatile long startedAtMs;
    private volatile long lastNotifyMs;
    private long lastFileTouchMs;
    private volatile long speedSampleAtMs;
    private volatile long speedSampleBytes;
    private volatile long bytesPerSecond;
    private volatile long knownDurationMs;
    private volatile float playbackRate = 1.0f;
    private volatile long playbackPositionMs;
    private volatile long throttleWindowAtMs;
    private volatile long throttleWindowBytes;

    private Thread downloadThread;
    private HttpURLConnection remoteConnection;
    private LocalHttpServer localServer;
    private volatile String remoteUrl;

    PlaybackCache(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start(String base, String path, long knownTotalBytes) throws Exception {
        start(base, path, knownTotalBytes, 0L);
    }

    void start(String base, String path, long knownTotalBytes, long durationMs) throws Exception {
        stopInternal(false);
        cancelled = false;
        deleteWhenStopped = false;
        playbackEstablished = false;
        complete = false;
        failed = false;
        error = "";
        totalBytes = knownTotalBytes > 0 ? knownTotalBytes : -1L;
        knownDurationMs = Math.max(0L, durationMs);
        playbackRate = 1.0f;
        playbackPositionMs = 0L;
        throttleWindowAtMs = 0L;
        throttleWindowBytes = 0L;
        startedAtMs = System.currentTimeMillis();
        lastNotifyMs = 0L;
        lastFileTouchMs = 0L;
        speedSampleAtMs = startedAtMs;
        speedSampleBytes = 0L;
        bytesPerSecond = 0L;
        waitingClients.set(0);
        metadataRequests.set(0);
        waitingOffset.set(-1L);
        userSeekByte.set(-1L);
        remoteUrl = base + "/download?path=" + Util.enc(path);
        cacheFile = CacheFiles.file(context, base, path);

        synchronized (CacheFiles.lock(base, path)) {
            File parent = cacheFile.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                throw new IllegalStateException("Не удалось создать временную папку");
            }
            downloadedBytes = cacheFile.exists() ? cacheFile.length() : 0L;
            if (totalBytes > 0 && downloadedBytes > totalBytes) {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
                downloadedBytes = 0L;
            }
            if (totalBytes > 0 && downloadedBytes == totalBytes) complete = true;
            speedSampleBytes = downloadedBytes;
            PlaybackDiagnostics.log(context, "cache start path=" + path + " existing="
                    + downloadedBytes + " total=" + totalBytes);

            localServer = new LocalHttpServer();
            localServer.start();
            if (!complete) {
                downloadThread = new Thread(this::download, "playback-cache-download");
                downloadThread.setDaemon(true);
                downloadThread.start();
            } else {
                notifyChanged(true);
            }
        }
    }

    String localUrl() {
        LocalHttpServer server = localServer;
        return server == null ? null : "http://127.0.0.1:" + server.port() + "/current";
    }

    File getCacheFile() { return cacheFile; }
    long getDownloadedBytes() { return downloadedBytes; }
    long getTotalBytes() { return totalBytes; }
    long getStartedAtMs() { return startedAtMs; }
    long getBytesPerSecond() { return bytesPerSecond; }

    long getPrepareTargetBytes() {
        long total = totalBytes;
        if (total <= 0) return MAX_PREPARE_BYTES;
        long thirtyPercent = (total * 3L + 9L) / 10L;
        return Math.min(thirtyPercent, MAX_PREPARE_BYTES);
    }

    /** Строгий порог либо безопасный динамический старт по скорости и запасу. */
    boolean isReadyToPlay(long durationMs, float rate) {
        if (complete || downloadedBytes >= getPrepareTargetBytes()) return true;
        long elapsed = System.currentTimeMillis() - startedAtMs;
        if (elapsed < 7000L) return false;
        long speed = bytesPerSecond;
        if (speed <= 0) return false;
        long duration = durationMs > 0 ? durationMs : knownDurationMs;
        float safeRate = rate > 0f ? rate : 1f;
        if (duration > 0 && totalBytes > 0) {
            double mediaBps = (double) totalBytes * 1000.0 / (double) duration;
            double requiredBps = mediaBps * safeRate * 1.35;
            double bufferedMs = downloadedBytes * 1000.0 / Math.max(1.0, mediaBps * safeRate);
            return downloadedBytes >= MIN_DYNAMIC_BYTES
                    && speed >= requiredBps
                    && bufferedMs >= 60000.0;
        }
        // Если длительность ещё не известна, оцениваем максимально допустимый
        // битрейт так, будто серия длится не меньше 20 минут. Для обычных серий
        // это консервативно, но не требует ждать строгие 30%/300 МБ.
        if (totalBytes > 0) {
            double assumedMediaBps = (double) totalBytes * 1000.0 / UNKNOWN_DURATION_ASSUMED_MS;
            double requiredBps = assumedMediaBps * safeRate * 1.50;
            double bufferedMs = downloadedBytes * 1000.0 / Math.max(1.0, assumedMediaBps * safeRate);
            return downloadedBytes >= MIN_DYNAMIC_BYTES
                    && speed >= requiredBps
                    && bufferedMs >= 60000.0;
        }
        return false;
    }


    boolean isComplete() { return complete; }
    boolean isFailed() { return failed; }
    String getError() { return error; }
    boolean isWaitingForData() { return waitingClients.get() > 0; }
    long getWaitingOffset() { return waitingOffset.get(); }

    void markPlaybackEstablished() {
        playbackEstablished = true;
        throttleWindowAtMs = System.currentTimeMillis();
        throttleWindowBytes = downloadedBytes;
    }

    void updatePlaybackInfo(long durationMs, float rate, long positionMs) {
        if (durationMs > 0) knownDurationMs = durationMs;
        if (rate > 0f) playbackRate = rate;
        if (positionMs >= 0L) playbackPositionMs = positionMs;
    }

    void requestUserSeekByte(long target) {
        userSeekByte.set(Math.max(0L, target));
    }

    void stopServingKeepFile() {
        LocalHttpServer server = localServer;
        localServer = null;
        if (server != null) server.close();
    }

    void cancelAndDelete() { stopInternal(true); }
    void cancelKeepFile() { stopInternal(false); }

    private void download() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + 2); } catch (Throwable ignored) {}
        InputStream in = null;
        FileOutputStream out = null;
        TransferCoordinator.Lease transferLease = null;
        try {
            transferLease = TransferCoordinator.acquire(TransferCoordinator.PLAYBACK);
            if (cancelled) return;
            long existing = cacheFile.exists() ? cacheFile.length() : 0L;
            HttpURLConnection connection = openRemote(remoteUrl, existing > 0 ? "bytes=" + existing + "-" : null);
            int code = connection.getResponseCode();

            // Нельзя записывать хвост ответа 206 с неверным Content-Range как начало файла.
            // Некоторые серверы/прокси также отвечают 416, если частичный файл устарел.
            if (existing > 0L && (code == 416 || (code == 206 && !validContentRange(connection, existing)))) {
                PlaybackDiagnostics.log(context, "cache resume rejected code=" + code + " existing=" + existing);
                connection.disconnect();
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
                existing = 0L;
                connection = openRemote(remoteUrl, null);
                code = connection.getResponseCode();
            }

            remoteConnection = connection;
            if (cancelled) return;
            if (code != 200 && code != 206) throw new IllegalStateException("HTTP " + code);
            App.markPaired(context, connection);

            boolean append = code == 206 && existing > 0 && validContentRange(connection, existing);
            if (!append) existing = 0L;
            long responseTotal = responseTotalBytes(connection, existing, append);
            if (responseTotal > 0) totalBytes = responseTotal;
            if (totalBytes <= 0) throw new IllegalStateException("Сервер не сообщил размер файла");
            if (existing > totalBytes) {
                existing = 0L;
                append = false;
            }
            long usable = cacheFile.getParentFile().getUsableSpace();
            long remaining = Math.max(0L, totalBytes - existing);
            if (usable > 0 && remaining > Math.max(0L, usable - STORAGE_RESERVE_BYTES)) {
                throw new IllegalStateException("Недостаточно свободного места");
            }
            downloadedBytes = existing;
            speedSampleBytes = existing;
            notifyChanged(true);

            in = new BufferedInputStream(connection.getInputStream(), DOWNLOAD_BUFFER_SIZE);
            out = new FileOutputStream(cacheFile, append);
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
            int read;
            while (!cancelled && (read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloadedBytes += read;
                touchCacheFile(false);
                updateSpeed();
                synchronized (dataLock) { dataLock.notifyAll(); }
                notifyChanged(false);
                throttleBackgroundWrite();
            }
            out.flush();
            if (!cancelled) {
                if (downloadedBytes != totalBytes) throw new IllegalStateException("Файл загружен не полностью");
                complete = true;
                touchCacheFile(true);
                PlaybackDiagnostics.log(context, "cache complete bytes=" + downloadedBytes);
                synchronized (dataLock) { dataLock.notifyAll(); }
                notifyChanged(true);
            }
        } catch (Throwable e) {
            if (!cancelled) {
                failed = true;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                PlaybackDiagnostics.log(context, "cache failed: " + error);
                synchronized (dataLock) { dataLock.notifyAll(); }
                notifyChanged(true);
            }
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (transferLease != null) transferLease.release();
            HttpURLConnection connection = remoteConnection;
            if (connection != null) connection.disconnect();
            remoteConnection = null;
            if (cancelled && deleteWhenStopped && cacheFile != null) {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
            }
        }
    }


    private void touchCacheFile(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastFileTouchMs < FILE_TOUCH_INTERVAL_MS) return;
        lastFileTouchMs = now;
        //noinspection ResultOfMethodCallIgnored
        cacheFile.setLastModified(now);
    }

    /**
     * Когда данных уже много, не даём фоновой записи забрать весь CPU/I/O у
     * декодера. Ограничение включается только при запасе примерно на 90 секунд.
     */
    private void throttleBackgroundWrite() throws InterruptedException {
        if (!playbackEstablished || complete || cancelled) return;
        long total = totalBytes;
        long mediaDuration = knownDurationMs;
        if (total <= 0L || mediaDuration <= 0L) return;
        double mediaBps = (double) total * 1000.0 / (double) mediaDuration;
        double consumptionBps = mediaBps * Math.max(0.5f, playbackRate);
        long expectedPositionBytes = (long) Math.min((double) total,
                (double) total * Math.max(0L, playbackPositionMs) / (double) mediaDuration);
        long aheadBytes = downloadedBytes - expectedPositionBytes;
        if (aheadBytes < (long) (consumptionBps * 180.0)) {
            throttleWindowAtMs = System.currentTimeMillis();
            throttleWindowBytes = downloadedBytes;
            return;
        }
        long targetBps = Math.max(2L * 1024L * 1024L, (long) (consumptionBps * 3.0));
        long now = System.currentTimeMillis();
        if (throttleWindowAtMs <= 0L || now - throttleWindowAtMs > 5000L
                || downloadedBytes < throttleWindowBytes) {
            throttleWindowAtMs = now;
            throttleWindowBytes = downloadedBytes;
            return;
        }
        long bytes = downloadedBytes - throttleWindowBytes;
        long desiredMs = bytes * 1000L / Math.max(1L, targetBps);
        long actualMs = now - throttleWindowAtMs;
        long sleepMs = desiredMs - actualMs;
        if (sleepMs > 0L) Thread.sleep(Math.min(50L, sleepMs));
    }

    private void updateSpeed() {
        long now = System.currentTimeMillis();
        long elapsed = now - speedSampleAtMs;
        if (elapsed < 1000L) return;
        long instant = (downloadedBytes - speedSampleBytes) * 1000L / Math.max(1L, elapsed);
        bytesPerSecond = bytesPerSecond <= 0 ? instant : (bytesPerSecond * 3L + instant) / 4L;
        speedSampleAtMs = now;
        speedSampleBytes = downloadedBytes;
    }

    private HttpURLConnection openRemote(String rawUrl, String rangeHeader) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            App.auth(connection, context);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (rangeHeader != null) connection.setRequestProperty("Range", rangeHeader);
            int code = connection.getResponseCode();
            if (code == 403 && attempt == 0 && App.retryPairingAfterForbidden(context, connection)) {
                connection.disconnect();
                continue;
            }
            return connection;
        }
        throw new IllegalStateException("HTTP 403");
    }

    private void notifyChanged(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotifyMs < 500L) return;
        lastNotifyMs = now;
        if (listener != null) listener.onCacheChanged();
    }

    private void stopInternal(boolean deleteFile) {
        synchronized (stopLock) {
            deleteWhenStopped = deleteWhenStopped || deleteFile;
            cancelled = true;
            HttpURLConnection connection = remoteConnection;
            if (connection != null) connection.disconnect();
            LocalHttpServer server = localServer;
            localServer = null;
            if (server != null) server.close();
            synchronized (dataLock) { dataLock.notifyAll(); }
            Thread thread = downloadThread;
            downloadThread = null;
            boolean stopped = thread == null || thread == Thread.currentThread();
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
                try { thread.join(350L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                stopped = !thread.isAlive();
            }
            // Нельзя удалять файл, пока FileOutputStream ещё может писать в него.
            // Если поток не успел завершиться, он удалит файл сам в finally.
            if (deleteFile && stopped && cacheFile != null) {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
            }
        }
    }

    private static boolean validContentRange(HttpURLConnection connection, long start) {
        String value = connection.getHeaderField("Content-Range");
        if (value == null) return false;
        value = value.trim().toLowerCase(Locale.US);
        return value.startsWith("bytes " + start + "-");
    }

    private static long responseTotalBytes(HttpURLConnection connection, long existing, boolean append) {
        long fromRange = totalFromContentRange(connection.getHeaderField("Content-Range"));
        if (fromRange > 0) return fromRange;
        long custom = parseLong(connection.getHeaderField("X-File-Size"), -1L);
        if (custom > 0) return custom;
        long length = parseLong(connection.getHeaderField("Content-Length"), -1L);
        if (length > 0) return append ? existing + length : length;
        return -1L;
    }

    private static long totalFromContentRange(String value) {
        if (value == null) return -1L;
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= value.length()) return -1L;
        return parseLong(value.substring(slash + 1).trim(), -1L);
    }

    private static long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private final class LocalHttpServer {
        private final Set<Socket> activeSockets = Collections.synchronizedSet(new HashSet<Socket>());
        private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
                1, 3, 20L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(4),
                r -> {
                    Thread t = new Thread(r, "playback-cache-client");
                    t.setDaemon(true);
                    return t;
                });
        private ServerSocket serverSocket;
        private Thread acceptThread;
        private volatile boolean closed;

        LocalHttpServer() {
            workers.allowCoreThreadTimeOut(true);
        }

        void start() throws Exception {
            serverSocket = new ServerSocket(0, 6, InetAddress.getByName("127.0.0.1"));
            serverSocket.setSoTimeout(1000);
            acceptThread = new Thread(this::acceptLoop, "playback-cache-http");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() { return serverSocket == null ? -1 : serverSocket.getLocalPort(); }

        void close() {
            closed = true;
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
            synchronized (activeSockets) {
                for (Socket socket : activeSockets) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
                activeSockets.clear();
            }
            workers.shutdownNow();
            if (acceptThread != null && acceptThread != Thread.currentThread()) {
                acceptThread.interrupt();
                try { acceptThread.join(600L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        private void acceptLoop() {
            while (!closed && !cancelled) {
                try {
                    final Socket socket = serverSocket.accept();
                    socket.setSoTimeout(30000);
                    try { socket.setSendBufferSize(SOCKET_SEND_BUFFER_SIZE); } catch (Throwable ignored) {}
                    try { socket.setTcpNoDelay(true); } catch (Throwable ignored) {}
                    activeSockets.add(socket);
                    try {
                        workers.execute(() -> handle(socket));
                    } catch (RejectedExecutionException e) {
                        activeSockets.remove(socket);
                        try { writeSimple(socket, 503, "Service Unavailable", ""); } catch (Exception ignored) {}
                        try { socket.close(); } catch (Exception ignored) {}
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (Throwable e) {
                    if (!closed && !cancelled) {
                        failed = true;
                        error = "Ошибка локального кэша";
                        notifyChanged(true);
                    }
                    break;
                }
            }
        }

        private void handle(Socket socket) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "ISO-8859-1"));
                String requestLine = reader.readLine();
                if (requestLine == null) return;
                int firstSpace = requestLine.indexOf(' ');
                String method = (firstSpace > 0 ? requestLine.substring(0, firstSpace) : requestLine)
                        .toUpperCase(Locale.US);
                String rangeHeader = null;
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                        rangeHeader = line.substring(colon + 1).trim();
                    }
                }
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    writeSimple(socket, 405, "Method Not Allowed", "");
                    return;
                }
                long total = waitForTotal();
                if (total <= 0) {
                    writeSimple(socket, 503, "Service Unavailable", "");
                    return;
                }
                Range range = parseRange(rangeHeader, total);
                if (range == null) {
                    OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                    writeAscii(out, "HTTP/1.1 416 Range Not Satisfiable\r\n");
                    writeAscii(out, "Content-Range: bytes */" + total + "\r\nConnection: close\r\n\r\n");
                    out.flush();
                    return;
                }

                if (isMetadataTailRequest(rangeHeader, range, total)
                        && reserveMetadataRequest()
                        && proxyRemoteRange(socket, method, range, total)) return;

                if (isUnsupportedInitialRange(rangeHeader, range, total)) {
                    failed = true;
                    error = "Контейнер требует прямое воспроизведение";
                    synchronized (dataLock) { dataLock.notifyAll(); }
                    notifyChanged(true);
                    writeSimple(socket, 503, "Service Unavailable", "");
                    return;
                }

                boolean partial = rangeHeader != null;
                long length = range.end - range.start + 1L;
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE);
                writeAscii(out, partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                writeAscii(out, "Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n");
                writeAscii(out, "Content-Length: " + length + "\r\n");
                if (partial) writeAscii(out, "Content-Range: bytes " + range.start + "-" + range.end + "/" + total + "\r\n");
                writeAscii(out, "Connection: close\r\n\r\n");
                out.flush();
                if ("HEAD".equals(method)) return;

                if (range.start >= downloadedBytes && !complete) {
                    boolean userSeek = isUserSeek(range.start);
                    long ahead = userSeek ? USER_SEEK_AHEAD_BYTES : DECODER_AHEAD_BYTES;
                    waitForBytes(Math.min(total, range.start + ahead));
                    if (userSeek) userSeekByte.set(-1L);
                }
                streamRange(out, range.start, range.end);
            } catch (OutOfMemoryError oom) {
                failed = true;
                error = "Недостаточно памяти для локального потока";
                synchronized (dataLock) { dataLock.notifyAll(); }
                notifyChanged(true);
            } catch (Throwable ignored) {
            } finally {
                activeSockets.remove(socket);
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        private boolean isUserSeek(long start) {
            long target = userSeekByte.get();
            if (target < 0) return false;
            return Math.abs(start - target) <= 16L * 1024L * 1024L
                    || (target > downloadedBytes && start >= downloadedBytes);
        }

        private boolean isMetadataTailRequest(String header, Range range, long total) {
            if (header == null || playbackEstablished || complete || failed || cancelled) return false;
            long length = range.end - range.start + 1L;
            return range.start >= Math.max(0L, total - METADATA_TAIL_WINDOW_BYTES)
                    && range.start > downloadedBytes + 4L * 1024L * 1024L
                    && length > 0 && length <= MAX_METADATA_RANGE_BYTES;
        }

        private boolean reserveMetadataRequest() {
            while (true) {
                int current = metadataRequests.get();
                if (current >= 2) return false;
                if (metadataRequests.compareAndSet(current, current + 1)) return true;
            }
        }

        private boolean isUnsupportedInitialRange(String header, Range range, long total) {
            if (header == null || playbackEstablished || complete) return false;
            if (isUserSeek(range.start)) return false;
            if (range.start <= downloadedBytes + 4L * 1024L * 1024L) return false;
            long tailStart = Math.max(0L, total - METADATA_TAIL_WINDOW_BYTES);
            return range.start < tailStart;
        }

        private boolean proxyRemoteRange(Socket socket, String method, Range range, long total) {
            HttpURLConnection connection = null;
            InputStream in = null;
            TransferCoordinator.Lease transferLease = null;
            boolean startedResponse = false;
            try {
                transferLease = TransferCoordinator.acquire(TransferCoordinator.METADATA);
                if (cancelled) return false;
                PlaybackDiagnostics.log(context, "metadata range " + range.start + "-" + range.end);
                connection = openRemote(remoteUrl, "bytes=" + range.start + "-" + range.end);
                if (connection.getResponseCode() != 206) return false;
                App.markPaired(context, connection);
                long length = range.end - range.start + 1L;
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE);
                writeAscii(out, "HTTP/1.1 206 Partial Content\r\nContent-Type: application/octet-stream\r\n");
                writeAscii(out, "Accept-Ranges: bytes\r\nContent-Length: " + length + "\r\n");
                writeAscii(out, "Content-Range: bytes " + range.start + "-" + range.end + "/" + total + "\r\nConnection: close\r\n\r\n");
                out.flush();
                startedResponse = true;
                if ("HEAD".equals(method)) return true;
                in = new BufferedInputStream(connection.getInputStream(), DOWNLOAD_BUFFER_SIZE);
                byte[] buffer = new byte[STREAM_BUFFER_SIZE];
                long remaining = length;
                while (!cancelled && remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min((long) buffer.length, remaining));
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                out.flush();
                return true;
            } catch (Throwable ignored) {
                return startedResponse;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (connection != null) connection.disconnect();
                if (transferLease != null) transferLease.release();
            }
        }

        private long waitForTotal() throws InterruptedException {
            synchronized (dataLock) {
                while (!cancelled && !failed && totalBytes <= 0) dataLock.wait(500L);
                return totalBytes;
            }
        }

        private void waitForBytes(long wanted) throws InterruptedException {
            waitingClients.incrementAndGet();
            updateWaitingOffset(wanted);
            notifyChanged(true);
            try {
                synchronized (dataLock) {
                    while (!cancelled && !failed && !complete && downloadedBytes < wanted) dataLock.wait(500L);
                }
            } finally {
                if (waitingClients.decrementAndGet() <= 0) waitingOffset.set(-1L);
                notifyChanged(true);
            }
        }

        private void updateWaitingOffset(long wanted) {
            long old;
            do {
                old = waitingOffset.get();
                if (old >= wanted) return;
            } while (!waitingOffset.compareAndSet(old, wanted));
        }

        private void streamRange(OutputStream out, long start, long end) throws Exception {
            RandomAccessFile file = new RandomAccessFile(cacheFile, "r");
            try {
                long position = start;
                file.seek(start);
                byte[] buffer = new byte[STREAM_BUFFER_SIZE];
                while (!cancelled && position <= end) {
                    long available = Math.min(downloadedBytes, end + 1L) - position;
                    if (available <= 0) {
                        if (failed || complete) break;
                        waitForBytes(Math.min(end + 1L, position + DECODER_AHEAD_BYTES));
                        continue;
                    }
                    int count = (int) Math.min((long) buffer.length, available);
                    int read = file.read(buffer, 0, count);
                    if (read <= 0) {
                        synchronized (dataLock) { dataLock.wait(50L); }
                        file.seek(position);
                        continue;
                    }
                    out.write(buffer, 0, read);
                    position += read;
                }
                out.flush();
            } finally {
                file.close();
            }
        }

        private void writeSimple(Socket socket, int code, String reason, String body) throws Exception {
            byte[] bytes = body.getBytes("UTF-8");
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            writeAscii(out, "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n");
            out.write(bytes);
            out.flush();
        }

        private void writeAscii(OutputStream out, String value) throws Exception {
            out.write(value.getBytes("ISO-8859-1"));
        }
    }

    private static final class Range {
        final long start;
        final long end;
        Range(long start, long end) { this.start = start; this.end = end; }
    }

    private static Range parseRange(String header, long total) {
        if (total <= 0) return null;
        if (header == null || header.trim().isEmpty()) return new Range(0L, total - 1L);
        String value = header.trim().toLowerCase(Locale.US);
        if (!value.startsWith("bytes=")) return null;
        value = value.substring(6).trim();
        if (value.contains(",")) return null;
        int dash = value.indexOf('-');
        if (dash < 0) return null;
        String left = value.substring(0, dash).trim();
        String right = value.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) return null;
                suffix = Math.min(suffix, total);
                start = total - suffix;
                end = total - 1L;
            } else {
                start = Long.parseLong(left);
                end = right.isEmpty() ? total - 1L : Long.parseLong(right);
                if (start < 0 || start >= total) return null;
                if (end >= total) end = total - 1L;
                if (end < start) return null;
            }
            return new Range(start, end);
        } catch (Exception e) {
            return null;
        }
    }
}
