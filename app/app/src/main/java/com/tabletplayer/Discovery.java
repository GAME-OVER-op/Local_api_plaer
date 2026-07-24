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

/** Общий UDP-поиск media-server в локальной сети (используется главным экраном и плеером). */
public class Discovery {
    public static class Server {
        public String host;
        public int port;
        public String name;
    }

    /** Синхронный поиск. Вызывать только в фоновом потоке. */
    public static List<Server> find(Context ctx, int port, int totalMs) {
        Map<String, Server> found = new LinkedHashMap<>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.setBroadcast(true);
            sock.setSoTimeout(400);
            byte[] msg = "MEDIA_DISCOVER".getBytes("UTF-8");
            send(sock, msg, "255.255.255.255", port);
            InetAddress sub = subnetBroadcast(ctx);
            if (sub != null) sock.send(new DatagramPacket(msg, msg.length, sub, port));
            long end = System.currentTimeMillis() + totalMs;
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
                    found.put(s.host + ":" + s.port, s);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (sock != null) sock.close();
        }
        return new ArrayList<>(found.values());
    }

    private static void send(DatagramSocket sock, byte[] msg, String addr, int port) {
        try {
            sock.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(addr), port));
        } catch (Exception ignored) {
        }
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
        } catch (Exception e) {
            return null;
        }
    }
}
