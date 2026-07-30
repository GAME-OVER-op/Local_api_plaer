package com.tabletplayer;

import android.content.Context;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Последовательно загружает один текущий файл и отдаёт уже полученные байты libVLC
 * через локальный HTTP-сервер. Удалённый сервер видит один продолжительный запрос.
 */
final class PlaybackCache {
    interface Listener {
        void onCacheChanged();
    }

    private static final long MAX_PREPARE_BYTES = 300L * 1024L * 1024L;
    private static final long SEEK_AHEAD_BYTES = 32L * 1024L * 1024L;
    private static final long METADATA_TAIL_WINDOW_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_METADATA_RANGE_BYTES = 32L * 1024L * 1024L;
    private static final long STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 256 * 1024;

    private final Context context;
    private final Listener listener;
    private final Object dataLock = new Object();
    private final Object stopLock = new Object();
    private final AtomicInteger waitingClients = new AtomicInteger();
    private final AtomicInteger metadataRequests = new AtomicInteger();
    private final AtomicLong waitingOffset = new AtomicLong(-1L);

    private final File cacheDir;
    private final File cacheFile;
    private volatile boolean cancelled;
    private volatile boolean deleteWhenStopped;
    private volatile boolean playbackEstablished;
    private volatile boolean complete;
    private volatile boolean failed;
    private volatile String error = "";
    private volatile long totalBytes = -1L;
    private volatile long downloadedBytes = 0L;
    private volatile long startedAtMs = 0L;
    private volatile long lastNotifyMs = 0L;

    private Thread downloadThread;
    private HttpURLConnection remoteConnection;
    private LocalHttpServer localServer;
    private volatile String remoteUrl;

    PlaybackCache(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        File external = context.getExternalCacheDir();
        cacheDir = new File(external != null ? external : context.getCacheDir(), "playback");
        cacheFile = new File(cacheDir, "current_" + Long.toHexString(System.nanoTime()) + ".part");
    }

    void start(final String base, final String path, long knownTotalBytes) throws Exception {
        stopInternal(true);
        cancelled = false;
        deleteWhenStopped = false;
        playbackEstablished = false;
        complete = false;
        failed = false;
        error = "";
        totalBytes = knownTotalBytes > 0 ? knownTotalBytes : -1L;
        downloadedBytes = 0L;
        startedAtMs = System.currentTimeMillis();
        waitingClients.set(0);
        metadataRequests.set(0);
        waitingOffset.set(-1L);
        remoteUrl = base + "/download?path=" + Util.enc(path);

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IllegalStateException("Не удалось создать временную папку");
        }
        cleanupOldFiles();
        if (cacheFile.exists() && !cacheFile.delete()) {
            throw new IllegalStateException("Не удалось очистить временный файл");
        }

        localServer = new LocalHttpServer();
        localServer.start();
        downloadThread = new Thread(() -> download(base, path), "playback-cache-download");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    String localUrl() {
        LocalHttpServer server = localServer;
        if (server == null) return null;
        return "http://127.0.0.1:" + server.port() + "/current";
    }

    long getDownloadedBytes() {
        return downloadedBytes;
    }

    long getTotalBytes() {
        return totalBytes;
    }

    long getStartedAtMs() {
        return startedAtMs;
    }

    long getPrepareTargetBytes() {
        long total = totalBytes;
        if (total <= 0) return MAX_PREPARE_BYTES;
        long thirtyPercent = (total * 3L + 9L) / 10L;
        return Math.min(thirtyPercent, MAX_PREPARE_BYTES);
    }

    boolean isReadyToPlay() {
        long target = getPrepareTargetBytes();
        return target > 0 && downloadedBytes >= target;
    }

    boolean isComplete() {
        return complete;
    }

    boolean isFailed() {
        return failed;
    }

    String getError() {
        return error;
    }

    boolean isWaitingForData() {
        return waitingClients.get() > 0;
    }

    long getWaitingOffset() {
        return waitingOffset.get();
    }

    void markPlaybackEstablished() {
        playbackEstablished = true;
    }

    void cancelAndDelete() {
        stopInternal(true);
    }

    private void download(String base, String path) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            if (cancelled) return;
            HttpURLConnection connection = openRemote(remoteUrl, null);
            remoteConnection = connection;
            if (cancelled) {
                connection.disconnect();
                return;
            }
            int code = connection.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            App.markPaired(context, connection);

            long responseTotal = responseTotalBytes(connection);
            if (responseTotal > 0) totalBytes = responseTotal;
            if (totalBytes <= 0) throw new IllegalStateException("Сервер не сообщил размер файла");
            long usable = cacheDir.getUsableSpace();
            if (usable > 0 && totalBytes > Math.max(0L, usable - STORAGE_RESERVE_BYTES)) {
                throw new IllegalStateException("Недостаточно свободного места");
            }
            notifyChanged(true);

            in = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
            out = new FileOutputStream(cacheFile, false);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while (!cancelled && (read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloadedBytes += read;
                synchronized (dataLock) {
                    dataLock.notifyAll();
                }
                notifyChanged(false);
            }
            out.flush();
            if (!cancelled) {
                if (downloadedBytes != totalBytes) {
                    throw new IllegalStateException("Файл загружен не полностью");
                }
                complete = true;
                synchronized (dataLock) {
                    dataLock.notifyAll();
                }
                notifyChanged(true);
            }
        } catch (Throwable e) {
            if (!cancelled) {
                failed = true;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                synchronized (dataLock) {
                    dataLock.notifyAll();
                }
                notifyChanged(true);
            }
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            HttpURLConnection connection = remoteConnection;
            if (connection != null) connection.disconnect();
            remoteConnection = null;
            if (cancelled && deleteWhenStopped) {
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
            }
        }
    }

    private HttpURLConnection openRemote(String rawUrl, String rangeHeader) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(rawUrl).openConnection();
            App.auth(connection, context);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (rangeHeader != null && !rangeHeader.isEmpty()) {
                connection.setRequestProperty("Range", rangeHeader);
            }
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
        if (!force && now - lastNotifyMs < 250L) return;
        lastNotifyMs = now;
        if (listener != null) listener.onCacheChanged();
    }

    private void cleanupOldFiles() {
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.equals(cacheFile)) {
                // Старые экземпляры используют уникальные имена, поэтому их можно
                // безопасно удалить без риска повредить новый поток.
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private void stopInternal(boolean deleteFile) {
        synchronized (stopLock) {
            cancelled = true;
            deleteWhenStopped |= deleteFile;
            synchronized (dataLock) {
                dataLock.notifyAll();
            }

            LocalHttpServer server = localServer;
            localServer = null;
            if (server != null) server.close();

            HttpURLConnection connection = remoteConnection;
            if (connection != null) connection.disconnect();

            Thread thread = downloadThread;
            downloadThread = null;
            if (thread != null) thread.interrupt();

            // Не блокируем UI ожиданием сетевого потока. У каждого экземпляра свой
            // файл; загрузчик удалит его в finally, а для уже завершённого потока
            // оставляем короткую отложенную очистку после закрытия локальных сокетов.
            if (deleteWhenStopped && cacheFile.exists() && (thread == null || !thread.isAlive())) {
                Thread cleanup = new Thread(() -> {
                    try { Thread.sleep(250L); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    //noinspection ResultOfMethodCallIgnored
                    cacheFile.delete();
                }, "playback-cache-cleanup");
                cleanup.setDaemon(true);
                cleanup.start();
            }
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * На старых версиях Android getHeaderField("Content-Length") иногда не
     * возвращает значение для нестандартно сформированного ответа. Поэтому
     * проверяем заголовки несколькими способами. Content-Range и X-File-Size
     * оставлены как резерв для совместимости с другими версиями сервера.
     */
    private static long responseTotalBytes(HttpURLConnection connection) {
        long total = parseLong(connection.getHeaderField("Content-Length"), -1L);
        if (total > 0) return total;

        total = parseLong(connection.getHeaderField("X-File-Size"), -1L);
        if (total > 0) return total;

        total = totalFromContentRange(connection.getHeaderField("Content-Range"));
        if (total > 0) return total;

        try {
            Map<String, List<String>> headers = connection.getHeaderFields();
            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if (key == null || entry.getValue() == null) continue;
                    for (String value : entry.getValue()) {
                        if ("content-length".equalsIgnoreCase(key)) {
                            total = parseLong(value, -1L);
                        } else if ("x-file-size".equalsIgnoreCase(key)) {
                            total = parseLong(value, -1L);
                        } else if ("content-range".equalsIgnoreCase(key)) {
                            total = totalFromContentRange(value);
                        }
                        if (total > 0) return total;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static long totalFromContentRange(String value) {
        if (value == null) return -1L;
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= value.length()) return -1L;
        String total = value.substring(slash + 1).trim();
        if ("*".equals(total)) return -1L;
        return parseLong(total, -1L);
    }

    private final class LocalHttpServer {
        private final ThreadPoolExecutor clients = new ThreadPoolExecutor(
                2, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                runnable -> {
                    Thread thread = new Thread(runnable, "playback-cache-client");
                    thread.setDaemon(true);
                    return thread;
                });
        private final Set<Socket> activeSockets = Collections.synchronizedSet(new HashSet<Socket>());
        private ServerSocket serverSocket;
        private Thread acceptThread;
        private volatile boolean closed;

        void start() throws Exception {
            serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            serverSocket.setSoTimeout(1000);
            acceptThread = new Thread(this::acceptLoop, "playback-cache-http");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return serverSocket == null ? 0 : serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed && !cancelled) {
                try {
                    final Socket socket = serverSocket.accept();
                    socket.setSoTimeout(30000);
                    activeSockets.add(socket);
                    try {
                        clients.execute(() -> handle(socket));
                    } catch (RejectedExecutionException rejected) {
                        activeSockets.remove(socket);
                        try { socket.close(); } catch (Exception ignoredClose) {}
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
                String[] requestParts = requestLine.split(" ");
                String method = requestParts.length > 0 ? requestParts[0].toUpperCase(Locale.US) : "";
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
                    writeAscii(out, "Content-Range: bytes */" + total + "\r\n");
                    writeAscii(out, "Connection: close\r\n\r\n");
                    out.flush();
                    return;
                }

                if (tryReserveMetadataRange(rangeHeader, range, total)
                        && proxyRemoteRange(socket, method, range, total)) {
                    return;
                }
                if (isUnsupportedInitialRange(rangeHeader, range)) {
                    failed = true;
                    error = "Контейнер требует прямое воспроизведение";
                    synchronized (dataLock) { dataLock.notifyAll(); }
                    notifyChanged(true);
                    writeSimple(socket, 503, "Service Unavailable", "");
                    return;
                }

                boolean partial = rangeHeader != null;
                long length = range.end - range.start + 1L;
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 128 * 1024);
                writeAscii(out, partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                writeAscii(out, "Content-Type: application/octet-stream\r\n");
                writeAscii(out, "Accept-Ranges: bytes\r\n");
                writeAscii(out, "Content-Length: " + length + "\r\n");
                if (partial) {
                    writeAscii(out, "Content-Range: bytes " + range.start + "-" + range.end + "/" + total + "\r\n");
                }
                writeAscii(out, "Connection: close\r\n\r\n");
                out.flush();
                if ("HEAD".equals(method)) return;

                long downloadedAtOpen = downloadedBytes;
                if (range.start >= downloadedAtOpen && !complete) {
                    long wanted = Math.min(total, range.start + SEEK_AHEAD_BYTES);
                    waitForBytes(wanted);
                }
                streamRange(out, range.start, range.end);
            } catch (Throwable ignored) {
            } finally {
                activeSockets.remove(socket);
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        private boolean tryReserveMetadataRange(String rangeHeader, Range range, long total) {
            if (rangeHeader == null || playbackEstablished || complete || failed || cancelled) return false;
            long downloaded = downloadedBytes;
            long length = range.end - range.start + 1L;
            long tailStart = Math.max(0L, total - METADATA_TAIL_WINDOW_BYTES);
            if (range.start <= downloaded + 4L * 1024L * 1024L
                    || range.start < tailStart
                    || length <= 0
                    || length > MAX_METADATA_RANGE_BYTES) {
                return false;
            }
            while (true) {
                int current = metadataRequests.get();
                if (current >= 2) return false;
                if (metadataRequests.compareAndSet(current, current + 1)) return true;
            }
        }

        private boolean isUnsupportedInitialRange(String rangeHeader, Range range) {
            return rangeHeader != null
                    && !playbackEstablished
                    && !complete
                    && range.start > downloadedBytes + 4L * 1024L * 1024L;
        }

        /**
         * Некоторые MP4/MOV при открытии читают небольшой индекс из хвоста файла.
         * Это не пользовательская перемотка: один короткий Range передаётся напрямую,
         * а основной файл продолжает последовательно загружаться одним потоком.
         */
        private boolean proxyRemoteRange(Socket socket, String method, Range range, long total) {
            HttpURLConnection connection = null;
            InputStream in = null;
            boolean responseStarted = false;
            try {
                String header = "bytes=" + range.start + "-" + range.end;
                connection = openRemote(remoteUrl, header);
                int code = connection.getResponseCode();
                if (code != 206) return false;
                App.markPaired(context, connection);

                long length = range.end - range.start + 1L;
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 128 * 1024);
                writeAscii(out, "HTTP/1.1 206 Partial Content\r\n");
                writeAscii(out, "Content-Type: application/octet-stream\r\n");
                writeAscii(out, "Accept-Ranges: bytes\r\n");
                writeAscii(out, "Content-Length: " + length + "\r\n");
                writeAscii(out, "Content-Range: bytes " + range.start + "-" + range.end + "/" + total + "\r\n");
                writeAscii(out, "Connection: close\r\n\r\n");
                out.flush();
                responseStarted = true;
                if ("HEAD".equals(method)) return true;

                in = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
                byte[] buffer = new byte[128 * 1024];
                long remaining = length;
                while (!cancelled && remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min((long) buffer.length, remaining));
                    if (read < 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                out.flush();
                // После отправки HTTP-заголовков этот сокет уже обработан даже при
                // преждевременном закрытии libVLC; второй ответ писать нельзя.
                return true;
            } catch (Throwable ignored) {
                return responseStarted;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (connection != null) connection.disconnect();
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
                    while (!cancelled && !failed && !complete && downloadedBytes < wanted) {
                        dataLock.wait(500L);
                    }
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
                byte[] buffer = new byte[128 * 1024];
                while (!cancelled && position <= end) {
                    long available = Math.min(downloadedBytes, end + 1L) - position;
                    if (available <= 0) {
                        if (failed || complete) break;
                        waitForBytes(Math.min(end + 1L, position + 1L));
                        continue;
                    }
                    int count = (int) Math.min((long) buffer.length, available);
                    file.seek(position);
                    int read = file.read(buffer, 0, count);
                    if (read <= 0) {
                        synchronized (dataLock) { dataLock.wait(100L); }
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

        void close() {
            closed = true;
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
            List<Socket> sockets;
            synchronized (activeSockets) {
                sockets = new ArrayList<>(activeSockets);
                activeSockets.clear();
            }
            for (Socket socket : sockets) {
                try { socket.close(); } catch (Exception ignored) {}
            }
            clients.shutdownNow();
            if (acceptThread != null) acceptThread.interrupt();
        }

        private void writeSimple(Socket socket, int code, String message, String body) throws Exception {
            byte[] bytes = body.getBytes("UTF-8");
            OutputStream out = socket.getOutputStream();
            writeAscii(out, "HTTP/1.1 " + code + " " + message + "\r\n");
            writeAscii(out, "Content-Type: text/plain; charset=utf-8\r\n");
            writeAscii(out, "Content-Length: " + bytes.length + "\r\n");
            writeAscii(out, "Connection: close\r\n\r\n");
            out.write(bytes);
            out.flush();
        }

        private void writeAscii(OutputStream out, String text) throws Exception {
            out.write(text.getBytes("ISO-8859-1"));
        }
    }

    private static final class Range {
        final long start;
        final long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static Range parseRange(String header, long total) {
        if (total <= 0) return null;
        if (header == null || header.trim().isEmpty()) return new Range(0L, total - 1L);
        String value = header.trim();
        if (!value.startsWith("bytes=") || value.indexOf(',') >= 0) return null;
        String spec = value.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        String left = spec.substring(0, dash).trim();
        String right = spec.substring(dash + 1).trim();
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
                if (start < 0 || start >= total) return null;
                end = right.isEmpty() ? total - 1L : Long.parseLong(right);
                if (end < start) return null;
                end = Math.min(end, total - 1L);
            }
            return new Range(start, end);
        } catch (Exception e) {
            return null;
        }
    }
}
