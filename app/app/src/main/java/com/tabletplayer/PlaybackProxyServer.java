package com.tabletplayer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * Минимальный локальный HTTP-прокси для будущего режима воспроизведения из
 * растущего кэша. В блоке B класс добавлен как изолированный компонент и ещё
 * не подключён к пользовательскому запуску видео.
 */
public final class PlaybackProxyServer implements AutoCloseable {
    public interface DataSource {
        File file();
        long totalBytes();
        long availableBytes();
        boolean complete();
        boolean waitForBytes(long bytes, long timeoutMs) throws InterruptedException;
    }

    private final DataSource source;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;

    public PlaybackProxyServer(DataSource source) {
        this.source = source;
    }

    public synchronized String start() throws IOException {
        if (running) return url();
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "PlaybackProxyServer");
        thread.setDaemon(true);
        thread.start();
        return url();
    }

    public synchronized String url() {
        if (server == null) return "";
        return "http://127.0.0.1:" + server.getLocalPort() + "/current";
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(new Client(s), "PlaybackProxyClient");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) close();
            }
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private final class Client implements Runnable {
        private final Socket socket;

        Client(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(15000);
                byte[] req = new byte[4096];
                int n = socket.getInputStream().read(req);
                if (n <= 0) return;
                String head = new String(req, 0, n, "ISO-8859-1");
                Range range = parseRange(head, source.totalBytes());
                serve(range);
            } catch (Throwable ignored) {
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }

        private void serve(Range range) throws Exception {
            long total = source.totalBytes();
            if (total <= 0) total = source.complete() ? source.availableBytes() : -1;
            long start = range.start;
            long end = range.end >= 0 ? range.end : (total > 0 ? total - 1 : Long.MAX_VALUE - 1);
            if (start < 0) start = 0;
            if (total > 0 && end >= total) end = total - 1;
            long firstNeed = start + 1;
            source.waitForBytes(firstNeed, 30000L);

            long available = source.availableBytes();
            if (available <= start) {
                writeStatus(416, "Range Not Satisfiable", 0, null);
                return;
            }
            long length;
            if (end == Long.MAX_VALUE - 1) {
                length = Math.max(1, available - start);
            } else {
                length = end - start + 1;
            }
            String contentRange = null;
            int code = range.requested ? 206 : 200;
            String status = range.requested ? "Partial Content" : "OK";
            if (range.requested && total > 0) {
                contentRange = "bytes " + start + "-" + end + "/" + total;
            }
            writeStatus(code, status, length, contentRange);
            copyBytes(start, length);
        }

        private void writeStatus(int code, String status, long length, String contentRange) throws IOException {
            OutputStream out = socket.getOutputStream();
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
            h.append("Content-Type: application/octet-stream\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("Connection: close\r\n");
            if (contentRange != null) h.append("Content-Range: ").append(contentRange).append("\r\n");
            if (length >= 0) h.append("Content-Length: ").append(length).append("\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes("ISO-8859-1"));
        }

        private void copyBytes(long start, long length) throws Exception {
            OutputStream out = socket.getOutputStream();
            RandomAccessFile raf = new RandomAccessFile(source.file(), "r");
            try {
                raf.seek(start);
                byte[] buf = new byte[64 * 1024];
                long sent = 0;
                while (sent < length && running) {
                    long absolute = start + sent;
                    int want = (int) Math.min(buf.length, length - sent);
                    source.waitForBytes(absolute + want, 30000L);
                    int r = raf.read(buf, 0, want);
                    if (r < 0) {
                        if (source.complete()) break;
                        Thread.sleep(120L);
                        continue;
                    }
                    out.write(buf, 0, r);
                    sent += r;
                }
                out.flush();
            } finally {
                raf.close();
            }
        }
    }

    private static Range parseRange(String header, long total) {
        String[] lines = header.split("\\r?\\n");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.US);
            if (!lower.startsWith("range:")) continue;
            int idx = lower.indexOf("bytes=");
            if (idx < 0) break;
            String spec = line.substring(idx + 6).trim();
            int dash = spec.indexOf('-');
            if (dash < 0) break;
            try {
                String a = spec.substring(0, dash).trim();
                String b = spec.substring(dash + 1).trim();
                if (a.length() == 0 && b.length() > 0 && total > 0) {
                    long suffix = Long.parseLong(b);
                    return new Range(Math.max(0, total - suffix), total - 1, true);
                }
                long start = a.length() == 0 ? 0 : Long.parseLong(a);
                long end = b.length() == 0 ? -1 : Long.parseLong(b);
                return new Range(start, end, true);
            } catch (Exception ignored) {
                break;
            }
        }
        return new Range(0, -1, false);
    }

    private static final class Range {
        final long start;
        final long end;
        final boolean requested;

        Range(long start, long end, boolean requested) {
            this.start = start;
            this.end = end;
            this.requested = requested;
        }
    }
}
