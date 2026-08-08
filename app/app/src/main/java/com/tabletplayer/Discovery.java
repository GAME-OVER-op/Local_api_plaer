package com.tabletplayer;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** UDP discovery with three background attempts and trusted server identity support. */
public class Discovery {
    public static class Server {
        public String host;
        public int port;
        public String name;
        public String id;
        public boolean trusted;
    }

    /** Synchronous. Always call from a background thread. */
    public static List<Server> find(Context ctx, int port, int totalMs) {
        Map<String, Server> found = new LinkedHashMap<>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(220);
            final byte[] plain = "MEDIA_DISCOVER".getBytes("UTF-8");
            final byte[] trusted = App.discoveryPacket(ctx).getBytes("UTF-8");
            final InetAddress sub = subnetBroadcast(ctx);
            final int perAttempt = Math.max(450, totalMs / 3);

            for (int attempt = 0; attempt < 3; attempt++) {
                send(sock, trusted, "255.255.255.255", port);
                send(sock, plain, "255.255.255.255", port);
                if (sub != null) {
                    try { sock.send(new DatagramPacket(trusted, trusted.length, sub, port)); } catch (Exception ignored) {}
                    try { sock.send(new DatagramPacket(plain, plain.length, sub, port)); } catch (Exception ignored) {}
                }

                long end = System.currentTimeMillis() + perAttempt;
                while (System.currentTimeMillis() < end) {
                    try {
                        byte[] buf = new byte[2048];
                        DatagramPacket r = new DatagramPacket(buf, buf.length);
                        sock.receive(r);
                        String body = new String(r.getData(), 0, r.getLength(), "UTF-8");
                        JSONObject o = new JSONObject(body);
                        if (!"media-server".equals(o.optString("app"))) continue;
                        Server s = new Server();
                        s.host = r.getAddress().getHostAddress();
                        s.name = o.optString("name", "media-server");
                        s.port = o.optInt("port", port);
                        s.id = o.optString("server_id", "");
                        s.trusted = o.optBoolean("trusted", false) && !s.id.isEmpty();
                        String key = s.host + ":" + s.port;
                        Server old = found.get(key);
                        if (old == null || (!old.trusted && s.trusted)) found.put(key, s);
                    } catch (java.net.SocketTimeoutException ignored) {
                    } catch (Exception ignored) {
                    }
                }
                if (attempt < 2) try { Thread.sleep(140L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
        return new ArrayList<>(found.values());
    }

    public static Server findTrusted(Context ctx, int port, int totalMs, String serverId) {
        if (serverId == null || serverId.isEmpty()) return null;
        for (Server s : find(ctx, port, totalMs)) {
            if (s.trusted && serverId.equals(s.id)) return s;
        }
        return null;
    }

    private static void send(DatagramSocket sock, byte[] msg, String addr, int port) {
        try { sock.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(addr), port)); }
        catch (Exception ignored) {}
    }

    private static InetAddress subnetBroadcast(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.ipAddress == 0) return null;
            int bc = (d.ipAddress & d.netmask) | ~d.netmask;
            byte[] q = new byte[4];
            for (int k = 0; k < 4; k++) q[k] = (byte) ((bc >> (k * 8)) & 0xFF);
            return InetAddress.getByAddress(q);
        } catch (Exception e) { return null; }
    }
}
