package com.tabletplayer;

import android.content.Context;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Versioned playback identity. All network and file reads run off the UI thread. */
public final class FileIdentity {
    private FileIdentity() {}

    public static final class Info {
        public String serverId;
        public String version;
        public String key;
        public long size;
    }

    public static String key(String serverId, String base, String path, String version) {
        if (version == null || version.isEmpty()) return "";
        String server = serverId == null || serverId.isEmpty() ? "legacy:" + base : serverId;
        return "media-v2:" + hash(server + "\n" + path + "\n" + version);
    }

    public static Info fetch(Context ctx, String base, String path, String expectedServer) throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        TransferCoordinator.Lease lease = null;
        try {
            lease = TransferCoordinator.get().tryAcquire(TransferCoordinator.Priority.PLAYBACK_METADATA, "file-version", 5000);
            if (lease == null) throw new java.io.IOException("Нет свободного соединения");
            c = (HttpURLConnection) new URL(base + "/file?path=" + Util.enc(path)).openConnection();
            App.auth(c, ctx);
            c.setUseCaches(false);
            c.setConnectTimeout(5000);
            c.setReadTimeout(40000); // Allows the first interactive approval.
            int status = c.getResponseCode();
            if (status != 200) throw new java.io.IOException(status == 404
                    ? "Обновите media-server: требуется API версии файла" : "HTTP " + status);
            in = c.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (body.size() + n > 65536) throw new java.io.IOException("Слишком большой ответ сервера");
                body.write(buffer, 0, n);
            }
            JSONObject json = new JSONObject(body.toString("UTF-8"));
            Info info = new Info();
            info.serverId = json.getString("server_id");
            info.version = json.getString("version");
            info.size = json.getLong("size");
            if (info.version.isEmpty() || info.serverId.isEmpty()) throw new java.io.IOException("Сервер не сообщил версию файла");
            if (expectedServer != null && !expectedServer.isEmpty() && !expectedServer.equals(info.serverId))
                throw new java.io.IOException("По этому адресу находится другой сервер");
            info.key = key(info.serverId, base, path, info.version);
            return info;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
            if (lease != null) lease.close();
        }
    }

    public static File metadataFile(File video) {
        return new File(video.getParentFile(), video.getName() + ".meta.json");
    }

    public static void saveDownload(File video, Info info) throws Exception {
        JSONObject json = new JSONObject();
        json.put("key", info.key);
        json.put("version", info.version);
        json.put("server_id", info.serverId);
        json.put("size", video.length());
        json.put("sample", sampleHash(video));
        File temp = new File(metadataFile(video).getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(json.toString().getBytes("UTF-8"));
        }
        File dest = metadataFile(video);
        if (dest.exists() && !dest.delete()) throw new java.io.IOException("Не удалось обновить сведения о загрузке");
        if (!temp.renameTo(dest)) throw new java.io.IOException("Не удалось сохранить сведения о загрузке");
    }

    public static String localKey(File video) throws Exception {
        String sample = sampleHash(video);
        File meta = metadataFile(video);
        if (meta.isFile() && meta.length() < 65536) {
            try (FileInputStream in = new FileInputStream(meta)) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048]; int n;
                while ((n = in.read(buffer)) != -1) body.write(buffer, 0, n);
                JSONObject json = new JSONObject(body.toString("UTF-8"));
                String key = json.optString("key", "");
                if (key.startsWith("media-v2:") && json.optLong("size", -1) == video.length()
                        && sample.equals(json.optString("sample", ""))) return key;
            } catch (Exception ignored) {}
        }
        return "local-v2:" + hash(video.getCanonicalPath() + "\n" + video.length()
                + "\n" + video.lastModified() + "\n" + sample);
    }

    // Bounded 192 KiB reads for manually replaced local downloads on a weak tablet.
    private static String sampleHash(File video) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (RandomAccessFile file = new RandomAccessFile(video, "r")) {
            long size = file.length();
            digest.update(Long.toString(size).getBytes("UTF-8"));
            byte[] buffer = new byte[64 * 1024];
            long[] offsets = {0, Math.max(0, (size - buffer.length) / 2), Math.max(0, size - buffer.length)};
            for (long offset : offsets) {
                file.seek(offset);
                int left = (int) Math.min(buffer.length, size - offset);
                while (left > 0) {
                    int n = file.read(buffer, 0, left);
                    if (n < 0) break;
                    digest.update(buffer, 0, n);
                    left -= n;
                }
            }
        }
        return hex(digest.digest());
    }

    public static String hash(String value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 255;
            result[i * 2] = alphabet[b >>> 4]; result[i * 2 + 1] = alphabet[b & 15];
        }
        return new String(result);
    }
}
