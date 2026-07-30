package com.tabletplayer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Фоновый сервис загрузок: переживает уход с экрана, отмена работает из шторки и из приложения.
 */
public class DownloadService extends Service {
    public static final String ACTION_START = "com.tabletplayer.DL_START";
    public static final String ACTION_CANCEL = "com.tabletplayer.DL_CANCEL";
    private static final String CH = "downloads";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    static final ConcurrentHashMap<Integer, Boolean> CANCELLED = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger(2000);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger active = new AtomicInteger(0);
    private boolean fg = false;

    public static int nextId() {
        return SEQ.incrementAndGet();
    }

    /** Папка загрузок приложения — только своё. */
    public static File downloadsDir() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TabletPlayer");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Уникальный путь загрузки строится из адреса сервера и полного удалённого пути.
     * Поэтому одинаковые имена из разных папок или серверов больше не смешиваются.
     */
    public static File targetFile(String base, String remotePath, String fallbackName) {
        File root = downloadsDir();
        File current = new File(root, serverFolder(base));
        List<String> parts = splitRemotePath(remotePath);
        if (parts.isEmpty()) parts.add(fallbackName == null ? "download" : fallbackName);
        for (int i = 0; i < parts.size() - 1; i++) current = new File(current, safeSegment(parts.get(i)));
        String last = safeSegment(parts.get(parts.size() - 1));
        if (last.isEmpty()) last = safeSegment(fallbackName == null ? "download" : fallbackName);
        return new File(current, last);
    }

    public static boolean isDownloaded(String base, String remotePath, String name) {
        File f = targetFile(base, remotePath, name);
        return f.isFile() && f.length() > 0;
    }

    public static String relativeDisplayPath(File file) {
        try {
            String root = downloadsDir().getCanonicalPath();
            String full = file.getCanonicalPath();
            if (full.startsWith(root + File.separator)) return full.substring(root.length() + 1);
        } catch (Exception ignored) {
        }
        return file.getName();
    }

    private static List<String> splitRemotePath(String path) {
        List<String> out = new ArrayList<>(8);
        if (path == null) return out;
        String[] raw = path.replace('\\', '/').split("/");
        for (String part : raw) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) continue;
            out.add(part);
        }
        return out;
    }

    private static String serverFolder(String base) {
        String label = "server";
        try {
            URL u = new URL(base);
            int port = u.getPort() >= 0 ? u.getPort() : u.getDefaultPort();
            label = u.getHost() + "_" + port;
        } catch (Exception ignored) {
        }
        return safeSegment(label) + "_" + shortHash(base == null ? "" : base);
    }

    private static String safeSegment(String value) {
        if (value == null) value = "";
        String original = value;
        StringBuilder out = new StringBuilder(value.length());
        boolean changed = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 32 || "\\/:*?\"<>|".indexOf(ch) >= 0) {
                out.append('_');
                changed = true;
            } else {
                out.append(ch);
            }
        }
        String s = out.toString().trim();
        if (s.isEmpty() || ".".equals(s) || "..".equals(s)) {
            s = "file";
            changed = true;
        }
        if (s.length() > 120) {
            s = s.substring(0, 100);
            changed = true;
        }
        if (changed) s = addHashBeforeExtension(s, shortHash(original));
        return s;
    }

    private static String addHashBeforeExtension(String name, String hash) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(0, dot) + "~" + hash + name.substring(dot);
        }
        return name + "~" + hash;
    }

    private static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(value.getBytes("UTF-8"));
            char[] out = new char[12];
            for (int i = 0; i < 6; i++) {
                int byteValue = b[i] & 0xff;
                out[i * 2] = HEX_DIGITS[byteValue >>> 4];
                out[i * 2 + 1] = HEX_DIGITS[byteValue & 15];
            }
            return new String(out);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) createChannelO();
    }

    private void createChannelO() {
        NotificationChannel ch = new NotificationChannel(CH, "Загрузки", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            int id = intent.getIntExtra("id", -1);
            if (id != -1) CANCELLED.put(id, true);
            if (active.get() <= 0) stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            final int id = intent.getIntExtra("id", nextId());
            final String base = intent.getStringExtra("base");
            final String path = intent.getStringExtra("path");
            final String name = intent.getStringExtra("name");
            final boolean install = intent.getBooleanExtra("install", false);
            active.incrementAndGet();
            NotificationCompat.Builder nb = builder(id, name).setContentText("Подготовка…").setProgress(0, 0, true);
            if (!fg) {
                startForeground(id, nb.build());
                fg = true;
            } else {
                NotificationManagerCompat.from(this).notify(id, nb.build());
            }
            io.execute(() -> download(id, base, path, name, install));
        }
        return START_NOT_STICKY;
    }

    private NotificationCompat.Builder builder(int id, String title) {
        Intent ci = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL).putExtra("id", id);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getService(this, id, ci, piFlags);
        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", pi);
    }

    private void download(int id, String base, String path, String name, boolean install) {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
        catch (Throwable ignored) {}
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        NotificationCompat.Builder nb = builder(id, name);
        boolean cancelled = false;
        File out = targetFile(base, path, name);
        File part = new File(out.getAbsolutePath() + ".part");
        long done = 0;
        long total = -1;

        TransferCoordinator.Lease transferLease = null;
        try {
            if (base == null || path == null || name == null) throw new IllegalArgumentException("нет данных загрузки");
            transferLease = TransferCoordinator.acquire(TransferCoordinator.MANUAL_DOWNLOAD);
            File parent = out.getParentFile();
            if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new RuntimeException("не удалось создать папку");

            for (int attempt = 0; attempt < 2; attempt++) {
                long existing = part.exists() ? part.length() : 0;
                HttpURLConnection c = null;
                InputStream in = null;
                FileOutputStream fos = null;
                try {
                    c = openDownloadConnection(base, path, existing);
                    int code = c.getResponseCode();

                    if (code == 416 && existing > 0 && attempt == 0) {
                        part.delete();
                        continue;
                    }
                    if (code != 200 && code != 206) throw new RuntimeException("HTTP " + code);
                    if (code == 206 && !validContentRange(c, existing)) {
                        if (attempt == 0) {
                            part.delete();
                            continue;
                        }
                        throw new RuntimeException("неверный диапазон ответа");
                    }
                    App.markPaired(this, c);

                    boolean append = code == 206;
                    if (!append) existing = 0;
                    total = totalLength(c, existing, append);
                    in = c.getInputStream();
                    fos = new FileOutputStream(part, append);
                    byte[] buf = new byte[256 * 1024];
                    done = existing;
                    int r;
                    int lastPct = -1;
                    long lastNotif = 0;
                    while ((r = in.read(buf)) != -1) {
                        if (Boolean.TRUE.equals(CANCELLED.remove(id))) {
                            cancelled = true;
                            break;
                        }
                        fos.write(buf, 0, r);
                        done += r;
                        long now = System.currentTimeMillis();
                        if (total > 0) {
                            int pct = (int) Math.min(100, done * 100 / total);
                            if (pct != lastPct && now - lastNotif > 300) {
                                lastPct = pct;
                                lastNotif = now;
                                nb.setProgress(100, pct, false).setContentText(pct + "%  ·  " + Util.humanSize(done));
                                nm.notify(id, nb.build());
                            }
                        } else if (now - lastNotif > 500) {
                            lastNotif = now;
                            nb.setProgress(0, 0, true).setContentText(Util.humanSize(done));
                            nm.notify(id, nb.build());
                        }
                    }
                    fos.flush();
                } finally {
                    try { if (fos != null) fos.close(); } catch (Exception ignored) {}
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    if (c != null) c.disconnect();
                }
                break;
            }

            if (!cancelled) {
                if (total > 0 && done != total) throw new RuntimeException("неверный размер загруженного файла");
                if (out.exists() && !out.delete()) throw new RuntimeException("не удалось заменить старый файл");
                if (!part.renameTo(out)) throw new RuntimeException("не удалось завершить файл");
            }
        } catch (Exception ex) {
            nb.setOngoing(false).setProgress(0, 0, false).setContentText("Ошибка: " + ex.getMessage()).setAutoCancel(true);
            nm.notify(id, nb.build());
            CANCELLED.remove(id);
            finishOne();
            return;
        } finally {
            if (transferLease != null) transferLease.release();
        }

        if (cancelled) {
            nm.cancel(id);
            part.delete();
            cleanupEmptyParents(part.getParentFile());
            toast("Загрузка отменена");
        } else {
            NotificationCompat.Builder doneNotification = new NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(name)
                    .setContentText("Готово")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            nm.notify(id, doneNotification.build());
            toast("Скачано: " + name);
            if (install) installApk(out);
        }
        CANCELLED.remove(id);
        finishOne();
    }

    private HttpURLConnection openDownloadConnection(String base, String path, long existing) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/download?path=" + Util.enc(path)).openConnection();
            App.auth(c, this);
            c.setConnectTimeout(8000);
            c.setReadTimeout(40000);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
            int code = c.getResponseCode();
            if (code == 403 && attempt == 0 && App.retryPairingAfterForbidden(this, c)) {
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new RuntimeException("HTTP 403");
    }

    private boolean validContentRange(HttpURLConnection c, long expectedStart) {
        String range = c.getHeaderField("Content-Range");
        if (range == null || !range.startsWith("bytes ")) return false;
        int dash = range.indexOf('-', 6);
        if (dash < 0) return false;
        try {
            return Long.parseLong(range.substring(6, dash).trim()) == expectedStart;
        } catch (Exception e) {
            return false;
        }
    }

    private long totalLength(HttpURLConnection c, long existing, boolean partial) {
        if (partial) {
            String range = c.getHeaderField("Content-Range");
            if (range != null) {
                int slash = range.lastIndexOf('/');
                if (slash >= 0) {
                    try { return Long.parseLong(range.substring(slash + 1).trim()); } catch (Exception ignored) {}
                }
            }
        }
        long len = contentLen(c);
        return partial && len > 0 ? existing + len : len;
    }

    private long contentLen(HttpURLConnection c) {
        if (Build.VERSION.SDK_INT >= 24) {
            long v = contentLenLong(c);
            if (v > 0) return v;
        }
        String h = c.getHeaderField("Content-Length");
        if (h != null) {
            try { return Long.parseLong(h.trim()); } catch (Exception ignored) {}
        }
        return -1;
    }

    private long contentLenLong(HttpURLConnection c) {
        return c.getContentLengthLong();
    }

    private void installApk(File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("Не удалось открыть установщик");
        }
    }

    public static void cleanupEmptyParents(File dir) {
        File root = downloadsDir();
        while (dir != null && !dir.equals(root)) {
            String[] children = dir.list();
            if (children == null || children.length != 0 || !dir.delete()) break;
            dir = dir.getParentFile();
        }
    }

    private void finishOne() {
        if (active.decrementAndGet() <= 0) {
            if (Build.VERSION.SDK_INT >= 24) stopFgDetach();
            else stopForeground(false);
            fg = false;
            stopSelf();
        }
    }

    private void stopFgDetach() {
        stopForeground(Service.STOP_FOREGROUND_DETACH);
    }

    private void toast(final String s) {
        ui.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
