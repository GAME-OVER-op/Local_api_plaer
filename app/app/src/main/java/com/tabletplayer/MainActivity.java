package com.tabletplayer;

import android.content.Intent;
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

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String KEY_IP = "ip";
    private static final String KEY_PORT = "port";
    private static final String KEY_HISTORY = "history";
    private static final int HISTORY_MAX = 8;

    private EditText ip, port;
    private ListView history, trustedList;
    private TextView historyEmpty, trustedEmpty;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Discovery.Server> discovered = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        setTitle(R.string.app_name);

        ip = findViewById(R.id.ip);
        port = findViewById(R.id.port);
        history = findViewById(R.id.history);
        historyEmpty = findViewById(R.id.history_empty);
        trustedList = findViewById(R.id.trusted_servers);
        trustedEmpty = findViewById(R.id.trusted_empty);
        Button connect = findViewById(R.id.connect);
        Button discover = findViewById(R.id.discover);

        ip.setText(App.prefs(this).getString(KEY_IP, ""));
        port.setText(App.prefs(this).getString(KEY_PORT, "10930"));
        connect.setOnClickListener(v -> doConnect());
        discover.setOnClickListener(v -> doDiscover(discover));
        renderTrusted();
        renderHistory();
    }

    private int portValue() {
        try {
            String p = port.getText().toString().trim();
            return p.isEmpty() ? 10930 : Integer.parseInt(p);
        } catch (Exception e) { return 10930; }
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
        Discovery.Server ds = discovered.get(host + ":" + p);
        openBrowse(host, p, ds == null ? "" : ds.name, ds == null ? "" : ds.id);
    }

    private void openBrowse(String host, int p, String name, String serverId) {
        Intent i = new Intent(this, BrowseActivity.class);
        i.putExtra("base", "http://" + host + ":" + p);
        i.putExtra("path", "");
        i.putExtra("server_name", name == null ? "" : name);
        i.putExtra("server_id", serverId == null ? "" : serverId);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void renderTrusted() {
        final List<UiStore.ServerRecord> records = UiStore.servers(this);
        trustedEmpty.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
        trustedList.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
        List<String> labels = new ArrayList<>();
        for (UiStore.ServerRecord r : records) labels.add("●  " + r.name + "\n    " + r.host + ":" + r.port);
        trustedList.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        trustedList.setOnItemClickListener((p, v, pos, id) -> reconnectTrusted(records.get(pos)));
    }

    /** Try the last IP first; if DHCP changed it, do 3 UDP discovery attempts in background. */
    private void reconnectTrusted(final UiStore.ServerRecord r) {
        Toast.makeText(this, "Подключение: " + r.name, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Identity id = verifyIdentity(r.host, r.port, r.id);
            if (id == null) {
                Discovery.Server s = Discovery.findTrusted(this, r.port, 2100, r.id);
                if (s != null) id = verifyIdentity(s.host, s.port, r.id);
            }
            final Identity result = id;
            ui.post(() -> {
                if (result == null) {
                    Toast.makeText(this, "Устройство «" + r.name + "» не найдено в сети", Toast.LENGTH_LONG).show();
                    return;
                }
                UiStore.saveServer(this, result.id, result.name, result.host, result.port);
                App.prefs(this).edit().putString(KEY_IP, result.host).putString(KEY_PORT, String.valueOf(result.port)).apply();
                ip.setText(result.host);
                port.setText(String.valueOf(result.port));
                renderTrusted();
                openBrowse(result.host, result.port, result.name, result.id);
            });
        }, "trusted-reconnect").start();
    }

    private static class Identity { String id, name, host; int port; }

    private Identity verifyIdentity(String host, int port, String expectedId) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + ":" + port + "/identity").openConnection();
            App.auth(c, this);
            c.setUseCaches(false);
            c.setConnectTimeout(1400);
            c.setReadTimeout(2200);
            c.setRequestProperty("Connection", "close");
            if (c.getResponseCode() != 200) return null;
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048]; int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            String id = o.optString("server_id", "");
            if (id.isEmpty() || (expectedId != null && !expectedId.isEmpty() && !expectedId.equals(id))) return null;
            Identity x = new Identity();
            x.id = id; x.name = o.optString("server_name", "media-server"); x.host = host; x.port = o.optInt("port", port);
            return x;
        } catch (Exception ignored) { return null; }
        finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
        }
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
            JSONArray out = new JSONArray(); for (String s : items) out.put(s);
            App.prefs(this).edit().putString(KEY_HISTORY, out.toString()).apply();
        } catch (Exception ignored) {}
        renderHistory();
    }

    private void renderHistory() {
        final List<String> items = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(App.prefs(this).getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < arr.length(); i++) items.add(arr.getString(i));
        } catch (Exception ignored) {}
        historyEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        history.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        history.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items));
        history.setOnItemClickListener((parent, view, pos, id) -> {
            String s = items.get(pos); int idx = s.lastIndexOf(':');
            if (idx <= 0) return;
            String host = s.substring(0, idx); int p = 10930;
            try { p = Integer.parseInt(s.substring(idx + 1)); } catch (Exception ignored) {}
            ip.setText(host); port.setText(String.valueOf(p));
            App.prefs(this).edit().putString(KEY_IP, host).putString(KEY_PORT, String.valueOf(p)).apply();
            addHistory(host + ":" + p);
            Discovery.Server ds = discovered.get(host + ":" + p);
            openBrowse(host, p, ds == null ? "" : ds.name, ds == null ? "" : ds.id);
        });
    }

    private void doDiscover(final Button btn) {
        btn.setEnabled(false); btn.setText("Поиск… (3 попытки)");
        final int p = portValue();
        new Thread(() -> {
            final List<Discovery.Server> found = Discovery.find(this, p, 2100);
            ui.post(() -> {
                btn.setEnabled(true); btn.setText("Найти серверы в сети"); showFound(found);
            });
        }, "server-discovery").start();
    }

    private void showFound(final List<Discovery.Server> list) {
        if (list.isEmpty()) { Toast.makeText(this, "Серверы не найдены", Toast.LENGTH_SHORT).show(); return; }
        final String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Discovery.Server s = list.get(i);
            labels[i] = (s.trusted ? "● " : "○ ") + s.name + "\n" + s.host + ":" + s.port;
            discovered.put(s.host + ":" + s.port, s);
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Найденные серверы")
                .setItems(labels, (dialog, which) -> {
                    Discovery.Server s = list.get(which);
                    ip.setText(s.host); port.setText(String.valueOf(s.port));
                    App.prefs(this).edit().putString(KEY_IP, s.host).putString(KEY_PORT, String.valueOf(s.port)).apply();
                    if (s.trusted) UiStore.saveServer(this, s.id, s.name, s.host, s.port);
                    renderTrusted();
                })
                .setNegativeButton("Закрыть", null).show();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) { getMenuInflater().inflate(R.menu.main_menu, menu); return true; }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left); return true;
        }
        return super.onOptionsItemSelected(item);
    }
    @Override protected void onResume() { super.onResume(); renderTrusted(); renderHistory(); }
}
