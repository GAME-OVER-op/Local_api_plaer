package com.tabletplayer;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatDelegate;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class App extends Application {
    public static final String PREFS = "tablet_player";
    public static final String KEY_THEME = "theme";
    private static final String KEY_DEVICE_SECRET = "device_secret_v1";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

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

    public static String deviceId(Context c) {
        String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.isEmpty()) id = "unknown-device";
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

    public static String deviceSecret(Context c) {
        SharedPreferences p = prefs(c);
        String secret = p.getString(KEY_DEVICE_SECRET, "");
        if (secret != null && secret.length() >= 64) return secret;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        secret = hex(bytes);
        p.edit().putString(KEY_DEVICE_SECRET, secret).apply();
        return secret;
    }

    public static void auth(HttpURLConnection c, Context ctx) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = nonce();
        URL url = c.getURL();
        String endpoint = url.getPath();
        String rel = queryGet(url.getQuery(), "path");
        String search = queryGet(url.getQuery(), "q");
        String payload = authPayload("GET", endpoint, rel, search, ts, nonce);
        String secret = deviceSecret(ctx);
        String sig = hmac(secret, payload);
        c.setRequestProperty("X-Device-Id", deviceId(ctx));
        c.setRequestProperty("X-Device-Name", deviceName());
        c.setRequestProperty("X-Auth-Ts", ts);
        c.setRequestProperty("X-Auth-Nonce", nonce);
        c.setRequestProperty("X-Auth-Sign", sig);
        c.setRequestProperty("X-Auth-Secret", secret);
    }

    public static String authQuery(Context ctx, String endpoint, String rel, String search) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = nonce();
        String payload = authPayload("GET", endpoint, rel == null ? "" : rel, search == null ? "" : search, ts, nonce);
        String sig = hmac(deviceSecret(ctx), payload);
        return "&dev=" + Util.enc(deviceId(ctx))
                + "&dn=" + Util.enc(deviceName())
                + "&ts=" + Util.enc(ts)
                + "&nonce=" + Util.enc(nonce)
                + "&sig=" + Util.enc(sig);
    }

    private static String authPayload(String method, String endpoint, String rel, String search, String ts, String nonce) {
        return method.toUpperCase(Locale.US) + "\n"
                + endpoint + "\n"
                + (rel == null ? "" : rel) + "\n"
                + (search == null ? "" : search) + "\n"
                + ts + "\n"
                + nonce;
    }

    private static String queryGet(String query, String key) {
        if (query == null || key == null) return "";
        String[] parts = query.split("&");
        for (String part : parts) {
            int eq = part.indexOf('=');
            String k = eq >= 0 ? part.substring(0, eq) : part;
            if (!key.equals(k)) continue;
            String v = eq >= 0 ? part.substring(eq + 1) : "";
            try {
                return URLDecoder.decode(v.replace("+", " "), "UTF-8");
            } catch (Exception e) {
                return v;
            }
        }
        return "";
    }

    public static String discoveryPacket(Context ctx) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = nonce();
        String id = deviceId(ctx);
        String payload = "DISCOVER\n" + id + "\n" + ts + "\n" + nonce;
        return "MEDIA_DISCOVER_V2|" + id + "|" + ts + "|" + nonce + "|" + hmac(deviceSecret(ctx), payload);
    }

    private static String nonce() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return hex(bytes);
    }

    private static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"));
            return hex(mac.doFinal(payload.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 15];
        }
        return new String(out);
    }
}
