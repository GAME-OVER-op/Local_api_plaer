package com.tabletplayer;

import android.app.Notification;
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
import java.security.MessageDigest;
import java.net.HttpURLConnection;
import java.net.URL;
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

    static final ConcurrentHashMap<Integer, Boolean> CANCELLED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Progress> PROGRESS = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger(2000);

    public static class Progress {
        public int id;
        public String base = "";
        public String path = "";
        public String name = "";
        public long done;
        public long total;
        public long bytesPerSec;
        public boolean preparing;

        public int percent() { return total > 0 ? (int) Math.min(100, done * 100L / total) : -1; }
    }

    public static List<Progress> activeProgress() {
        return new ArrayList<>(PROGRESS.values());
    }

    public static Progress progressFor(String base, String path) {
        for (Progress p : PROGRESS.values()) {
            if (same(base, p.base) && same(path, p.path)) return p;
        }
        return null;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

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

    public static File targetFile(String serverId, String base, String path, String name, String version) {
        File dir = new File(downloadsDir(), shortHash(FileIdentity.key(serverId, base, path, version)));
        return new File(dir, safeName(name));
    }

    private static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "file";
        return name.replace('/', '_').replace('\\', '_').replace('\0', '_');
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes("UTF-8"));
            char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[16];
            for (int i = 0; i < 8; i++) {
                int byteValue = b[i] & 0xff;
                out[i * 2] = hex[byteValue >>> 4];
                out[i * 2 + 1] = hex[byteValue & 15];
            }
            return new String(out);
        } catch (Exception e) {
            return String.valueOf(Math.abs(s.hashCode()));
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
            final String serverId = intent.getStringExtra("server_id");
            final String version = intent.getStringExtra("version");
            Progress initial = new Progress();
            initial.id = id; initial.base = base == null ? "" : base; initial.path = path == null ? "" : path; initial.name = name == null ? "" : name; initial.preparing = true;
            PROGRESS.put(id, initial);
            active.incrementAndGet();
            NotificationCompat.Builder nb = builder(id, name).setContentText("Подготовка…").setProgress(0, 0, true);
            if (!fg) {
                startForeground(id, nb.build());
                fg = true;
            } else {
                NotificationManagerCompat.from(this).notify(id, nb.build());
            }
            io.execute(new Runnable() {
                @Override
                public void run() {
                    download(id, base, path, name, install, serverId, version);
                }
            });
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

    private void download(int id, String base, String path, String name, boolean install, String serverId, String expectedVersion) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        NotificationCompat.Builder nb = builder(id, name);
        File part = null, out = null;
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        TransferCoordinator.Lease lease = null;
        boolean cancelled = false;
        Exception failure = null;
        try {
            if (Boolean.TRUE.equals(CANCELLED.get(id))) throw new InterruptedException("cancelled");
            FileIdentity.Info info = FileIdentity.fetch(this, base, path, serverId);
            if (expectedVersion != null && !expectedVersion.isEmpty() && !expectedVersion.equals(info.version))
                throw new java.io.IOException("Файл изменился. Обновите папку и повторите загрузку");
            lease = TransferCoordinator.get().acquire(TransferCoordinator.Priority.MANUAL_DOWNLOAD, name);
            if (Boolean.TRUE.equals(CANCELLED.get(id))) throw new InterruptedException("cancelled");
            out = targetFile(info.serverId, base, path, name, info.version);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new java.io.IOException("Не удалось создать папку загрузки");
            part = new File(out.getPath() + ".part");
            long existing = part.isFile() ? part.length() : 0;
            if (existing > info.size) { part.delete(); existing = 0; }
            if (existing < info.size || !part.isFile()) {
                c = (HttpURLConnection) new URL(base + "/download?path=" + Util.enc(path)).openConnection();
                App.auth(c, this);
                c.setRequestProperty("If-Match", "\"" + info.version + "\"");
                c.setConnectTimeout(8000); c.setReadTimeout(40000);
                if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
                int code = c.getResponseCode();
                if (code == 412) throw new java.io.IOException("Файл изменился во время загрузки");
                if (code == 200) existing = 0;
                else if (code != 206) throw new java.io.IOException("HTTP " + code);
                if (code == 206) {
                    String range = c.getHeaderField("Content-Range");
                    if (range == null || !range.startsWith("bytes " + existing + "-") || !range.endsWith("/" + info.size))
                        throw new java.io.IOException("Неверный диапазон докачки");
                }
                in = c.getInputStream();
                fos = new FileOutputStream(part, existing > 0);
                byte[] buffer = new byte[65536];
                long done = existing, lastNotification = 0, speedBytes = done, speedAt = System.currentTimeMillis();
                Progress progress = PROGRESS.get(id);
                if (progress != null) { progress.done = done; progress.total = info.size; progress.preparing = false; }
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Boolean.TRUE.equals(CANCELLED.get(id)) || Thread.currentThread().isInterrupted())
                        throw new InterruptedException("cancelled");
                    if (done + n > info.size) throw new java.io.IOException("Размер ответа больше размера файла");
                    fos.write(buffer, 0, n); done += n;
                    long now = System.currentTimeMillis();
                    if (progress != null) {
                        progress.done = done;
                        if (now - speedAt >= 500) {
                            progress.bytesPerSec = Math.max(0, (done - speedBytes) * 1000L / (now - speedAt));
                            speedBytes = done; speedAt = now;
                        }
                    }
                    if (now - lastNotification > 500) {
                        int percent = info.size > 0 ? (int) (done * 100L / info.size) : 100;
                        nb.setProgress(100, percent, false).setContentText(percent + "% · " + Util.humanSize(done));
                        nm.notify(id, nb.build()); lastNotification = now;
                    }
                }
                fos.flush(); fos.close(); fos = null;
                if (done != info.size) throw new java.io.IOException("Файл получен не полностью");
            }
            if (Boolean.TRUE.equals(CANCELLED.get(id))) throw new InterruptedException("cancelled");
            // Recheck before publishing: the server file may have changed after the last read.
            if (lease != null) { lease.close(); lease = null; }
            FileIdentity.Info current = FileIdentity.fetch(this, base, path, info.serverId);
            if (!info.version.equals(current.version)) throw new java.io.IOException("Файл изменился во время загрузки");
            if (out.exists() && !out.delete()) throw new java.io.IOException("Не удалось заменить файл");
            if (!part.renameTo(out)) throw new java.io.IOException("Не удалось завершить загрузку");
            FileIdentity.saveDownload(out, info);
        } catch (Exception e) {
            cancelled = e instanceof InterruptedException || Boolean.TRUE.equals(CANCELLED.get(id));
            if (!cancelled) failure = e;
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
            if (lease != null) lease.close();
            CANCELLED.remove(id); PROGRESS.remove(id);
        }
        if (cancelled) {
            if (part != null) part.delete();
            nm.cancel(id); toast("Загрузка отменена");
        } else if (failure != null) {
            nb.setOngoing(false).setProgress(0, 0, false).setContentText("Ошибка: " + failure.getMessage()).setAutoCancel(true);
            nm.notify(id, nb.build());
        } else {
            NotificationCompat.Builder done = new NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(name)
                    .setContentText("Готово").setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_LOW);
            nm.notify(id, done.build()); toast("Скачано: " + name);
            if (install && out != null) installApk(out);
        }
        finishOne();
    }

    private long contentLen(HttpURLConnection c) {
        if (Build.VERSION.SDK_INT >= 24) {
            long v = contentLenLong(c);
            if (v > 0) return v;
        }
        String h = c.getHeaderField("Content-Length");
        if (h != null) {
            try {
                return Long.parseLong(h.trim());
            } catch (Exception ignored) {
            }
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

    private void finishOne() {
        active.decrementAndGet();
        ui.post(() -> {
            if (active.get() <= 0) {
                if (Build.VERSION.SDK_INT >= 24) stopFgDetach();
                else stopForeground(false);
                fg = false; stopSelf();
            } else {
                Progress next = null;
                for (Progress progress : PROGRESS.values())
                    if (next == null || progress.id < next.id) next = progress;
                if (next != null) startForeground(next.id, builder(next.id, next.name)
                        .setContentText("Загрузка…").setProgress(0, 0, true).build());
            }
        });
    }

    private void stopFgDetach() {
        stopForeground(Service.STOP_FOREGROUND_DETACH);
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), s, Toast.LENGTH_SHORT).show();
            }
        });
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
