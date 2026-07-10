package com.tabletplayer;

import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String KEY_IP = "ip";
    private static final String KEY_PORT = "port";
    private static final String KEY_HISTORY = "history";
    private static final int HISTORY_MAX = 8;

    private EditText ip, port;
    private ListView history;
    private TextView historyEmpty;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        ip = findViewById(R.id.ip);
        port = findViewById(R.id.port);
        history = findViewById(R.id.history);
        historyEmpty = findViewById(R.id.history_empty);
        Button connect = findViewById(R.id.connect);
        Button discover = findViewById(R.id.discover);

        ip.setText(App.prefs(this).getString(KEY_IP, ""));
        port.setText(App.prefs(this).getString(KEY_PORT, "10930"));

        connect.setOnClickListener(v -> doConnect());
        discover.setOnClickListener(v -> doDiscover(discover));

        renderHistory();
    }

    private int portValue() {
        try {
            String p = port.getText().toString().trim();
            return p.isEmpty() ? 10930 : Integer.parseInt(p);
        } catch (Exception e) {
            return 10930;
        }
    }

    private void doConnect() {
        String host = ip.getText().toString().trim();
        int p = portValue();
        if (host.isEmpty()) {
            Toast.makeText(this, "Введите IP-адрес", Toast.LENGTH_SHORT).show();
            return;
        }
        App.prefs(this).edit().putString(KEY_IP, host).putString(KEY_PORT, String.valueOf(p)).apply();
        addHistory(host + ":" + p);
        openBrowse(host, p);
    }

    private void openBrowse(String host, int p) {
        Intent i = new Intent(this, BrowseActivity.class);
        i.putExtra("base", "http://" + host + ":" + p);
        i.putExtra("path", "");
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void addHistory(String entry) {
        try {
            JSONArray arr = new JSONArray(App.prefs(this).getString(KEY_HISTORY, "[]"));
            List<String> items = new ArrayList<>();
            items.add(entry);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.getString(i);
                if (!s.equals(entry) && items.size() < HISTORY_MAX) items.add(s);
            }
            JSONArray out = new JSONArray();
            for (String s : items) out.put(s);
            App.prefs(this).edit().putString(KEY_HISTORY, out.toString()).apply();
        } catch (Exception ignored) {
        }
        renderHistory();
    }

    private void renderHistory() {
        final List<String> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(App.prefs(this).getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < arr.length(); i++) items.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        historyEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        history.setAdapter(ad);
        history.setOnItemClickListener((parent, view, pos, id) -> {
            String s = items.get(pos);
            int idx = s.lastIndexOf(':');
            if (idx > 0) {
                String host = s.substring(0, idx);
                int p = 10930;
                try { p = Integer.parseInt(s.substring(idx + 1)); } catch (Exception ignored) {}
                ip.setText(host);
                port.setText(String.valueOf(p));
                openBrowse(host, p);
            }
        });
    }

    private void doDiscover(final Button btn) {
        btn.setEnabled(false);
        btn.setText("🔍 Поиск…");
        final int p = portValue();
        new Thread(() -> {
            final Map<String, JSONObject> found = new LinkedHashMap<>();
            DatagramSocket sock = null;
            try {
                sock = new DatagramSocket();
                sock.setBroadcast(true);
                sock.setSoTimeout(500);
                byte[] msg = "MEDIA_DISCOVER".getBytes("UTF-8");
                sendTo(sock, msg, "255.255.255.255", p);
                InetAddress sub = subnetBroadcast();
                if (sub != null) sock.send(new DatagramPacket(msg, msg.length, sub, p));
                long end = System.currentTimeMillis() + 1800;
                while (System.currentTimeMillis() < end) {
                    try {
                        byte[] buf = new byte[2048];
                        DatagramPacket r = new DatagramPacket(buf, buf.length);
                        sock.receive(r);
                        String body = new String(r.getData(), 0, r.getLength(), "UTF-8");
                        JSONObject o = new JSONObject(body);
                        if (!"media-server".equals(o.optString("app"))) continue;
                        String host = r.getAddress().getHostAddress();
                        JSONObject info = new JSONObject();
                        info.put("host", host);
                        info.put("name", o.optString("name", "media-server"));
                        info.put("port", o.optInt("port", p));
                        found.put(host + ":" + info.getInt("port"), info);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (sock != null) sock.close();
            }
            ui.post(() -> {
                btn.setEnabled(true);
                btn.setText("🔍 Найти серверы в сети");
                showFound(new ArrayList<>(found.values()));
            });
        }).start();
    }

    private void sendTo(DatagramSocket sock, byte[] msg, String addr, int p) {
        try {
            sock.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(addr), p));
        } catch (Exception ignored) {
        }
    }

    private InetAddress subnetBroadcast() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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

    private void showFound(final List<JSONObject> list) {
        if (list.isEmpty()) {
            Toast.makeText(this, "Серверы не найдены", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            JSONObject o = list.get(i);
            labels[i] = o.optString("name") + "\n" + o.optString("host") + ":" + o.optInt("port");
        }
        new AlertDialog.Builder(this)
                .setTitle("Найденные серверы")
                .setItems(labels, (dialog, which) -> {
                    JSONObject o = list.get(which);
                    String host = o.optString("host");
                    int p = o.optInt("port", 10930);
                    ip.setText(host);
                    port.setText(String.valueOf(p));
                    App.prefs(this).edit().putString(KEY_IP, host).putString(KEY_PORT, String.valueOf(p)).apply();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderHistory();
    }
}
