package com.tabletplayer;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Небольшой кольцевой журнал для редких сбоев Java/libVLC. */
final class PlaybackDiagnostics {
    private static final Object LOCK = new Object();
    private static final long MAX_BYTES = 512L * 1024L;

    private PlaybackDiagnostics() {}

    static void log(Context context, String message) {
        if (context == null || message == null) return;
        synchronized (LOCK) {
            try {
                File file = externalFile(context);
                if (file.exists() && file.length() > MAX_BYTES) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                FileOutputStream out = new FileOutputStream(file, true);
                out.write((time + "  " + message + "\n").getBytes("UTF-8"));
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static String readTail(Context context, int maxBytes) {
        synchronized (LOCK) {
            try {
                File file = externalFile(context);
                if (!file.exists()) return "";
                long length = file.length();
                int count = (int) Math.min(Math.max(0, maxBytes), length);
                byte[] data = new byte[count];
                FileInputStream in = new FileInputStream(file);
                long skip = Math.max(0L, length - count);
                while (skip > 0) {
                    long done = in.skip(skip);
                    if (done <= 0) break;
                    skip -= done;
                }
                int offset = 0;
                while (offset < count) {
                    int read = in.read(data, offset, count - offset);
                    if (read < 0) break;
                    offset += read;
                }
                in.close();
                return new String(data, 0, offset, "UTF-8");
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    private static File externalFile(Context context) {
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root != null) return new File(root, "tablet_player_playback.log");
        } catch (Throwable ignored) {
        }
        return new File(context.getCacheDir(), "tablet_player_playback.log");
    }
}
