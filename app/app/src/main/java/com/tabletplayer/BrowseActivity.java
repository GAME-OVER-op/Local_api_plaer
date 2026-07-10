package com.tabletplayer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BrowseActivity extends AppCompatActivity {
    static final String ACTION_CANCEL = "com.tabletplayer.CANCEL_DL";
    static final ConcurrentHashMap<Integer, Boolean> CANCELLED = new ConcurrentHashMap<>();
    static final AtomicInteger NOTIF_SEQ = new AtomicInteger(1000);

    private String base;
    private String path = "";
    private boolean ascending = true;
    private boolean searching = false;
    private String lastQuery = "";

    private ListView listView;
    private EditText searchBox;
    private TextView header, empty;
    private Button sortBtn;
    private SwipeRefreshLayout swipe;
    private final List<Entry> entries = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FileAdapter adapter;
    private boolean queueMode = false;
    private final List<String> queuePaths = new ArrayList<>();
    private final List<String> queueNames = new ArrayList<>();
    private View queueBar;
    private TextView queueInfo;
    private Button queueBtn, queueClear, queuePlay;

    private final BroadcastReceiver cancelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int id = i.getIntExtra("id", -1);
            if (id != -1) CANCELLED.put(id, true);
        }
    };

    static class Entry {
        String name;
        boolean isDir;
        long size;
        String fullPath;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_browse);
        base = getIntent().getStringExtra("base");
        path = getIntent().getStringExtra("path");
        if (path == null) path = "";

        listView = findViewById(R.id.list);
        searchBox = findViewById(R.id.search);
        header = findViewById(R.id.header);
        empty = findViewById(R.id.empty);
        sortBtn = findViewById(R.id.sort_btn);
        swipe = findViewById(R.id.swipe);
        Button searchBtn = findViewById(R.id.search_btn);
        queueBar = findViewById(R.id.queue_bar);
        queueInfo = findViewById(R.id.queue_info);
        queueBtn = findViewById(R.id.queue_btn);
        queueClear = findViewById(R.id.queue_clear);
        queuePlay = findViewById(R.id.queue_play);
        queueBtn.setOnClickListener(v -> { queueMode = !queueMode; updateQueueUi(); adapter.notifyDataSetChanged(); });
        queueClear.setOnClickListener(v -> { queuePaths.clear(); queueNames.clear(); updateQueueUi(); adapter.notifyDataSetChanged(); });
        queuePlay.setOnClickListener(v -> playQueue());
        updateQueueUi();

        adapter = new FileAdapter();
        listView.setAdapter(adapter);

        sortBtn.setOnClickListener(v -> {
            ascending = !ascending;
            updateSortLabel();
            resortAndShow();
        });
        updateSortLabel();

        searchBtn.setOnClickListener(v -> runSearch());
        searchBox.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });

        swipe.setOnRefreshListener(() -> {
            if (searching) doSearch(lastQuery);
            else loadList(path);
        });

        listView.setOnItemClickListener((parent, view, pos, id) -> onItemClick(entries.get(pos)));

        registerReceiver(cancelReceiver, new IntentFilter(ACTION_CANCEL));
        loadList(path);
    }

    private void updateSortLabel() {
        sortBtn.setText(ascending ? "Имя ↑" : "Имя ↓");
    }

    private void runSearch() {
        String q = searchBox.getText().toString().trim();
        if (q.isEmpty()) {
            searching = false;
            loadList(path);
            return;
        }
        doSearch(q);
    }

    private void loadList(final String p) {
        searching = false;
        header.setText("/" + p);
        swipe.setRefreshing(true);
        io.execute(() -> {
            try {
                String body = httpGet(base + "/list?path=" + Util.enc(p));
                JSONObject o = new JSONObject(body);
                JSONArray arr = o.getJSONArray("entries");
                final List<Entry> loaded = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.getJSONObject(i);
                    Entry en = new Entry();
                    en.name = e.getString("name");
                    en.isDir = e.getBoolean("is_dir");
                    en.size = e.optLong("size", 0);
                    en.fullPath = p.isEmpty() ? en.name : p + "/" + en.name;
                    loaded.add(en);
                }
                ui.post(() -> {
                    path = p;
                    entries.clear();
                    entries.addAll(loaded);
                    resortAndShow();
                    swipe.setRefreshing(false);
                });
            } catch (Exception ex) {
                showError(ex);
            }
        });
    }

    private void doSearch(final String q) {
        lastQuery = q;
        searching = true;
        header.setText("🔍 " + q);
        swipe.setRefreshing(true);
        io.execute(() -> {
            try {
                String body = httpGet(base + "/search?q=" + Util.enc(q) + "&path=" + Util.enc(path));
                JSONObject o = new JSONObject(body);
                JSONArray arr = o.getJSONArray("entries");
                final List<Entry> loaded = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.getJSONObject(i);
                    Entry en = new Entry();
                    en.name = e.getString("name");
                    en.isDir = e.getBoolean("is_dir");
                    en.size = e.optLong("size", 0);
                    en.fullPath = e.getString("path");
                    loaded.add(en);
                }
                ui.post(() -> {
                    entries.clear();
                    entries.addAll(loaded);
                    resortAndShow();
                    swipe.setRefreshing(false);
                });
            } catch (Exception ex) {
                showError(ex);
            }
        });
    }

    private void showError(final Exception ex) {
        ui.post(() -> {
            swipe.setRefreshing(false);
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (msg != null && msg.contains("403")) msg = "Доступ отклонён сервером";
            Toast.makeText(this, "Ошибка: " + msg, Toast.LENGTH_LONG).show();
        });
    }

    private void resortAndShow() {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                int c = Util.naturalCompare(a.name, b.name);
                return ascending ? c : -c;
            }
        });
        adapter.notifyDataSetChanged();
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setLayoutAnimation(android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_anim));
        listView.scheduleLayoutAnimation();
    }

    private void onItemClick(Entry e) {
        if (queueMode && !e.isDir && Util.isVideo(e.name)) {
            toggleQueue(e);
            return;
        }
        if (e.isDir) {
            searchBox.setText("");
            loadList(e.fullPath);
            return;
        }
        final boolean video = Util.isVideo(e.name);
        final String[] opts = video ? new String[]{"Смотреть", "Скачать"} : new String[]{"Скачать"};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(opts, (d, which) -> {
                    if (video && which == 0) openPlayer(e);
                    else startDownload(e);
                })
                .show();
    }

    private void toggleQueue(Entry e) {
        int idx = queuePaths.indexOf(e.fullPath);
        if (idx >= 0) {
            queuePaths.remove(idx);
            queueNames.remove(idx);
        } else {
            queuePaths.add(e.fullPath);
            queueNames.add(e.name);
        }
        updateQueueUi();
        adapter.notifyDataSetChanged();
    }

    private void updateQueueUi() {
        if (queueBtn != null) queueBtn.setText(queueMode ? "Очередь ✓" : "Очередь");
        if (queueBar != null) queueBar.setVisibility(queueMode ? View.VISIBLE : View.GONE);
        if (queueInfo != null) queueInfo.setText("В очереди: " + queuePaths.size());
    }

    private void playQueue() {
        if (queuePaths.isEmpty()) {
            Toast.makeText(this, "Очередь пуста — отметьте видео по порядку", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("base", base);
        i.putExtra("path", queuePaths.get(0));
        i.putExtra("name", queueNames.get(0));
        i.putExtra("folder", "");
        i.putExtra("queue_paths", queuePaths.toArray(new String[0]));
        i.putExtra("queue_names", queueNames.toArray(new String[0]));
        startActivity(i);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void openPlayer(Entry e) {
        String folder = "";
        int idx = e.fullPath.lastIndexOf('/');
        if (idx >= 0) folder = e.fullPath.substring(0, idx);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("base", base);
        i.putExtra("path", e.fullPath);
        i.putExtra("name", e.name);
        i.putExtra("folder", folder);
        startActivity(i);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void startDownload(final Entry e) {
        final int nid = NOTIF_SEQ.incrementAndGet();
        Toast.makeText(this, "Загрузка: " + e.name, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            NotificationManagerCompat nm = NotificationManagerCompat.from(this);
            NotificationCompat.Builder nb = new NotificationCompat.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(e.name)
                    .setContentText("Подготовка…")
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
            Intent ci = new Intent(ACTION_CANCEL).putExtra("id", nid);
            PendingIntent pi = PendingIntent.getBroadcast(this, nid, ci, PendingIntent.FLAG_UPDATE_CURRENT);
            nb.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отмена", pi);
            nm.notify(nid, nb.build());

            boolean cancelled = false;
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, e.name);
                long existing = out.exists() ? out.length() : 0;

                HttpURLConnection c = (HttpURLConnection) new URL(base + "/download?path=" + Util.enc(e.fullPath)).openConnection();
                App.auth(c, this);
                c.setConnectTimeout(8000);
                c.setReadTimeout(40000);
                if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
                int code = c.getResponseCode();

                boolean append;
                long total;
                if (code == 206) {
                    append = true;
                    total = existing + c.getContentLength();
                } else if (code == 200) {
                    append = false;
                    existing = 0;
                    total = c.getContentLength();
                } else {
                    throw new RuntimeException("HTTP " + code);
                }

                InputStream in = c.getInputStream();
                FileOutputStream fos = new FileOutputStream(out, append);
                byte[] buf = new byte[65536];
                long done = existing;
                int r;
                int lastPct = -1;
                while ((r = in.read(buf)) != -1) {
                    if (CANCELLED.remove(nid) != null) {
                        cancelled = true;
                        break;
                    }
                    fos.write(buf, 0, r);
                    done += r;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            nb.setProgress(100, pct, false).setContentText(pct + "%  ·  " + Util.humanSize(done));
                            nm.notify(nid, nb.build());
                        }
                    }
                }
                fos.flush();
                fos.close();
                in.close();
                c.disconnect();
            } catch (Exception ex) {
                nb.setOngoing(false).setProgress(0, 0, false).setContentText("Ошибка: " + ex.getMessage()).setAutoCancel(true);
                nm.notify(nid, nb.build());
                return;
            }

            if (cancelled) {
                nm.cancel(nid);
                ui.post(() -> Toast.makeText(this, "Загрузка отменена", Toast.LENGTH_SHORT).show());
            } else {
                nb.setOngoing(false).setProgress(0, 0, false)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentText("Готово")
                        .setAutoCancel(true);
                nm.notify(nid, nb.build());
                ui.post(() -> Toast.makeText(this, "Скачано: " + e.name, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String httpGet(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        App.auth(c, this);
        c.setConnectTimeout(8000);
        c.setReadTimeout(40000);
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new RuntimeException("HTTP " + code);
        }
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        in.close();
        c.disconnect();
        return bo.toString("UTF-8");
    }

    @Override
    public void onBackPressed() {
        if (searching) {
            searchBox.setText("");
            searching = false;
            loadList(path);
            return;
        }
        if (path != null && !path.isEmpty()) {
            int idx = path.lastIndexOf('/');
            loadList(idx >= 0 ? path.substring(0, idx) : "");
            return;
        }
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(cancelReceiver);
        } catch (Exception ignored) {
        }
        io.shutdownNow();
    }

    class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int i) {
            return entries.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = LayoutInflater.from(BrowseActivity.this).inflate(R.layout.list_item, parent, false);
            }
            Entry e = entries.get(pos);
            TextView icon = convert.findViewById(R.id.item_icon);
            TextView name = convert.findViewById(R.id.item_name);
            TextView sub = convert.findViewById(R.id.item_sub);
            TextView check = convert.findViewById(R.id.item_check);
            boolean video = Util.isVideo(e.name);
            icon.setText(e.isDir ? "📁" : (video ? "🎬" : "📄"));
            name.setText(e.name);
            if (e.isDir) {
                sub.setText("папка");
            } else {
                sub.setText(Util.humanSize(e.size));
            }
            boolean watched = !e.isDir && video && Store.isWatched(BrowseActivity.this, e.fullPath);
            check.setVisibility(watched ? View.VISIBLE : View.GONE);
            TextView queueNum = convert.findViewById(R.id.item_queue);
            if (queueMode && !e.isDir && video) {
                int qi = queuePaths.indexOf(e.fullPath);
                if (qi >= 0) {
                    queueNum.setText(String.valueOf(qi + 1));
                    queueNum.setVisibility(View.VISIBLE);
                } else {
                    queueNum.setVisibility(View.GONE);
                }
            } else {
                queueNum.setVisibility(View.GONE);
            }
            return convert;
        }
    }
}
