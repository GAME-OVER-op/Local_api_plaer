package com.tabletplayer;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class App extends Application {
    public static final String PREFS = "tablet_player";
    public static final String KEY_THEME = "theme";
    private static final String KEY_DEVICE_ID = "device_id_v2";
    private static final String KEY_DEVICE_SECRET = "device_secret_v1";
    private static final String KEY_PAIRED_PREFIX = "paired_v1_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static class AuthData {
        String time;
        String nonce;
        String proof;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
        applyTheme(isDark(this));
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                pw.println("=== Планшет Плеер: сбой ===");
                pw.println("Время: " + new java.util.Date().toString());
                pw.println("Поток: " + t.getName());
                pw.println("Устройство: " + deviceName() + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
                pw.println();
                e.printStackTrace(pw);
                pw.flush();
                java.io.File f = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "tablet_player_crash.txt");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(sw.toString().getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isDark(Context c) {
        return "dark".equals(prefs(c).getString(KEY_THEME, "light"));
    }

    public static void setDark(Context c, boolean dark) {
        prefs(c).edit().putString(KEY_THEME, dark ? "dark" : "light").apply();
        applyTheme(dark);
    }

    public static void applyTheme(boolean dark) {
        AppCompatDelegate.setDefaultNightMode(
                dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
    }

    public static synchronized String deviceId(Context c) {
        String id = prefs(c).getString(KEY_DEVICE_ID, "");
        if (id != null && id.length() == 32) return id;
        id = randomHex(16);
        prefs(c).edit().putString(KEY_DEVICE_ID, id).commit();
        return id;
    }

    public static String deviceName() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String mo = Build.MODEL == null ? "" : Build.MODEL;
        StringBuilder b = new StringBuilder();
        String n = (m + " " + mo).trim();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch >= 32 && ch < 127) b.append(ch);
        }
        String r = b.toString().trim();
        return r.isEmpty() ? "Android" : r;
    }

    /** Добавляет идентификатор и одноразовую HMAC-подпись к обычному HTTP-запросу приложения. */
    public static void auth(HttpURLConnection c, Context ctx) {
        try {
            URL url = c.getURL();
            AuthData a = authData(ctx, requestTarget(url));
            c.setRequestProperty("X-Device-Id", deviceId(ctx));
            c.setRequestProperty("X-Device-Name", deviceName());
            c.setRequestProperty("X-Auth-Time", a.time);
            c.setRequestProperty("X-Auth-Nonce", a.nonce);
            c.setRequestProperty("X-Auth-Proof", a.proof);
            if (!isPaired(ctx, url)) {
                // Секрет отправляется только при первичном подтверждении/перепривязке устройства.
                c.setRequestProperty("X-Device-Key", deviceSecret(ctx));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось подписать запрос", e);
        }
    }

    /** Подписанный URL для libVLC, который не умеет добавлять наши HTTP-заголовки. */
    public static String signedUrl(Context ctx, String rawUrl) {
        try {
            URL url = new URL(rawUrl);
            String target = requestTarget(url);
            String time = String.valueOf(System.currentTimeMillis() / 1000L);
            String proof = hmacHex(deviceSecret(ctx), "stream\n" + time + "\n" + target);
            StringBuilder out = new StringBuilder(rawUrl);
            out.append(rawUrl.contains("?") ? '&' : '?');
            out.append("dev=").append(Util.enc(deviceId(ctx)));
            out.append("&dn=").append(Util.enc(deviceName()));
            out.append("&mode=stream");
            out.append("&ts=").append(Util.enc(time));
            out.append("&sig=").append(Util.enc(proof));
            if (!isPaired(ctx, url)) out.append("&key=").append(Util.enc(deviceSecret(ctx)));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось подписать URL", e);
        }
    }

    /** После успешного ответа секрет больше не передаётся этому адресу открытым текстом. */
    public static void markPaired(Context ctx, HttpURLConnection c) {
        if (c != null) markPaired(ctx, c.getURL());
    }

    public static void markPaired(Context ctx, String base) {
        try {
            markPaired(ctx, new URL(base));
        } catch (Exception ignored) {
        }
    }

    /** После 403 один раз забывает локальный флаг, чтобы следующий запрос отправил ключ для перепривязки. */
    public static boolean retryPairingAfterForbidden(Context ctx, HttpURLConnection c) {
        if (c == null) return false;
        URL url = c.getURL();
        if (!isPaired(ctx, url)) return false;
        prefs(ctx).edit().remove(KEY_PAIRED_PREFIX + endpoint(url)).commit();
        return true;
    }

    private static void markPaired(Context ctx, URL url) {
        prefs(ctx).edit().putBoolean(KEY_PAIRED_PREFIX + endpoint(url), true).apply();
    }

    private static boolean isPaired(Context ctx, URL url) {
        return prefs(ctx).getBoolean(KEY_PAIRED_PREFIX + endpoint(url), false);
    }

    private static String endpoint(URL url) {
        int port = url.getPort();
        if (port < 0) port = url.getDefaultPort();
        return url.getProtocol() + "://" + url.getHost().toLowerCase() + ":" + port;
    }

    private static String requestTarget(URL url) {
        String f = url.getFile();
        return (f == null || f.isEmpty()) ? "/" : f;
    }

    private static AuthData authData(Context ctx, String target) throws Exception {
        AuthData a = new AuthData();
        a.time = String.valueOf(System.currentTimeMillis() / 1000L);
        a.nonce = randomHex(16);
        String message = a.time + "\n" + a.nonce + "\n" + target;
        a.proof = hmacHex(deviceSecret(ctx), message);
        return a;
    }

    private static String hmacHex(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
        return hex(mac.doFinal(message.getBytes("UTF-8")));
    }

    private static synchronized String deviceSecret(Context ctx) {
        String secret = prefs(ctx).getString(KEY_DEVICE_SECRET, "");
        if (secret != null && secret.length() >= 64) return secret;
        secret = randomHex(32);
        prefs(ctx).edit().putString(KEY_DEVICE_SECRET, secret).commit();
        return secret;
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return hex(b);
    }

    private static String hex(byte[] data) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 15];
        }
        return new String(out);
    }
}
