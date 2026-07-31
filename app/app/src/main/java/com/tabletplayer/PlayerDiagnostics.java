package com.tabletplayer;

import android.content.Context;
import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Небольшой диагностический журнал для событий плеера, кэша и локального прокси.
 * Пишется синхронно, но только на важных событиях, чтобы не нагружать старый планшет.
 */
public final class PlayerDiagnostics {
    private static final long MAX_LOG_BYTES = 512L * 1024L;
    private static final String LOG_NAME = "tablet_player_debug.txt";

    private PlayerDiagnostics() {}

    public static synchronized void log(Context context, String tag, String message) {
        BufferedWriter out = null;
        try {
            File file = logFile(context);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                File old = new File(file.getParentFile(), LOG_NAME + ".old");
                if (old.exists()) old.delete();
                if (!file.renameTo(old)) file.delete();
            }
            out = new BufferedWriter(new FileWriter(file, true));
            out.write(Long.toString(System.currentTimeMillis()));
            out.write("  ");
            out.write(tag == null ? "?" : tag);
            out.write("  ");
            out.write(message == null ? "" : message.replace('\n', ' '));
            out.newLine();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void log(Context context, String tag, Throwable error) {
        if (error == null) {
            log(context, tag, "null");
            return;
        }
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        log(context, tag, sw.toString());
    }

    private static File logFile(Context context) {
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root != null) return new File(root, LOG_NAME);
        } catch (Throwable ignored) {
        }
        if (context != null) return new File(context.getFilesDir(), LOG_NAME);
        return new File(LOG_NAME);
    }
}
