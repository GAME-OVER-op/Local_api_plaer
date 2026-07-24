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
import java.net.HttpURLConnection;
import java.net.URL;
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
            io.execute(new Runnable() {
                @Override
                public void run() {
                    download(id, base, path, name, install);
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

    private void download(int id, String base, String path, String name, boolean install) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        NotificationCompat.Builder nb = builder(id, name);
        boolean cancelled = false;
        File out = null;
        try {
            File dir = downloadsDir();
            out = new File(dir, name);
            long existing = out.exists() ? out.length() : 0;

            HttpURLConnection c = (HttpURLConnection) new URL(base + "/download?path=" + Util.enc(path)).openConnection();
            App.auth(c, this);
            c.setConnectTimeout(8000);
            c.setReadTimeout(40000);
            if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
            int code = c.getResponseCode();

            boolean append;
            long total;
            if (code == 206) {
                append = true;
                total = existing + contentLen(c);
            } else if (code == 200) {
                append = false;
                existing = 0;
                total = contentLen(c);
            } else {
                throw new RuntimeException("HTTP " + code);
            }

            InputStream in = c.getInputStream();
            FileOutputStream fos = new FileOutputStream(out, append);
            byte[] buf = new byte[65536];
            long done = existing;
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
                    int pct = (int) (done * 100 / total);
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
            fos.close();
            in.close();
            c.disconnect();
        } catch (Exception ex) {
            nb.setOngoing(false).setProgress(0, 0, false).setContentText("Ошибка: " + ex.getMessage()).setAutoCancel(true);
            nm.notify(id, nb.build());
            finishOne();
            return;
        }

        if (cancelled) {
            nm.cancel(id);
            if (out != null) out.delete();
            toast("Загрузка отменена");
        } else {
            NotificationCompat.Builder done = new NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(name)
                    .setContentText("Готово")
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            nm.notify(id, done.build());
            toast("Скачано: " + name);
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
