package com.tabletplayer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrowseActivity extends AppCompatActivity {
    private String base;
    private String path = "";
    private String serverName = "";
    private boolean ascending = true;
    private boolean searching = false;
    private String lastQuery = "";

    private ListView listView;
    private EditText searchBox;
    private TextView empty;
    private Button sortBtn;
    private LinearLayout crumbs;
    private SwipeRefreshLayout swipe;
    private final List<Entry> entries = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FileAdapter adapter;
    private final Set<String> downloaded = new HashSet<>();

    private boolean queueMode = false;
    private final List<String> queuePaths = new ArrayList<>();
    private final List<String> queueNames = new ArrayList<>();
    private View queueBar;
    private TextView queueInfo;
    private Button queueBtn, queueClear, queuePlay;

    private View undoBar;
    private TextView undoText;
    private Button undoCancel;
    private final Runnable hideUndo = new Runnable() {
        @Override
        public void run() {
            undoBar.setVisibility(View.GONE);
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
        serverName = getIntent().getStringExtra("server_name");
        if (path == null) path = "";
        if (serverName == null) serverName = "";

        listView = findViewById(R.id.list);
        searchBox = findViewById(R.id.search);
        empty = findViewById(R.id.empty);
        sortBtn = findViewById(R.id.sort_btn);
        crumbs = findViewById(R.id.crumbs);
        swipe = findViewById(R.id.swipe);
        Button searchBtn = findViewById(R.id.search_btn);
        ImageButton downloadsBtn = findViewById(R.id.downloads_btn);
        queueBar = findViewById(R.id.queue_bar);
        queueInfo = findViewById(R.id.queue_info);
        queueBtn = findViewById(R.id.queue_btn);
        queueClear = findViewById(R.id.queue_clear);
        queuePlay = findViewById(R.id.queue_play);
        undoBar = findViewById(R.id.undo_bar);
        undoText = findViewById(R.id.undo_text);
        undoCancel = findViewById(R.id.undo_cancel);

        downloadsBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, DownloadsActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        queueBtn.setOnClickListener(v -> {
            queueMode = !queueMode;
            updateQueueUi();
            adapter.notifyDataSetChanged();
        });
        queueClear.setOnClickListener(v -> {
            queuePaths.clear();
            queueNames.clear();
            updateQueueUi();
            adapter.notifyDataSetChanged();
        });
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
        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            showItemMenu(entries.get(pos));
            return true;
        });

        refreshDownloaded();
        loadList(path);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setCrumbs(String p) {
        crumbs.removeAllViews();
        crumbs.addView(makeCrumb("🏠", ""));
        if (p != null && !p.isEmpty()) {
            String[] parts = p.split("/");
            StringBuilder acc = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (acc.length() > 0) acc.append("/");
                acc.append(part);
                crumbs.addView(makeSep());
                crumbs.addView(makeCrumb(part, acc.toString()));
            }
        }
    }

    private void setSearchCrumb(String q) {
        crumbs.removeAllViews();
        TextView t = new TextView(this);
        t.setText("🔍 " + q);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setSingleLine(true);
        crumbs.addView(t);
    }

    private TextView makeCrumb(String label, final String target) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(14);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setSingleLine(true);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            searchBox.setText("");
            loadList(target);
        });
        return t;
    }

    private TextView makeSep() {
        TextView t = new TextView(this);
        t.setText("›");
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(14);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
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
        setCrumbs(p);
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
        setSearchCrumb(q);
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
        showItemMenu(e);
    }

    private void showItemMenu(final Entry e) {
        if (e.isDir) {
            searchBox.setText("");
            loadList(e.fullPath);
            return;
        }
        final boolean video = Util.isVideo(e.name);
        final boolean apk = Util.isApk(e.name);
        final List<String> opts = new ArrayList<>();
        if (video) opts.add("Смотреть");
        opts.add("Скачать");
        if (apk) opts.add("Скачать и установить");
        final String[] arr = opts.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(arr, (d, w) -> {
                    String o = arr[w];
                    if (o.equals("Смотреть")) openPlayer(e);
                    else if (o.equals("Скачать")) startDownload(e, false);
                    else startDownload(e, true);
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
        i.putExtra("server_name", serverName);
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
        i.putExtra("server_name", serverName);
        startActivity(i);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void startDownload(final Entry e, boolean install) {
        final int id = DownloadService.nextId();
        Intent i = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_START)
                .putExtra("id", id)
                .putExtra("base", base)
                .putExtra("path", e.fullPath)
                .putExtra("name", e.name)
                .putExtra("install", install);
        startService(i);
        showUndo(e.name, id);
    }

    private void showUndo(String name, final int id) {
        undoText.setText("Загрузка: " + name);
        undoBar.setVisibility(View.VISIBLE);
        undoCancel.setOnClickListener(v -> {
            Intent i = new Intent(this, DownloadService.class)
                    .setAction(DownloadService.ACTION_CANCEL)
                    .putExtra("id", id);
            startService(i);
            undoBar.setVisibility(View.GONE);
            Toast.makeText(this, "Отменено", Toast.LENGTH_SHORT).show();
        });
        ui.removeCallbacks(hideUndo);
        ui.postDelayed(hideUndo, 5000);
    }

    private void refreshDownloaded() {
        downloaded.clear();
        String[] names = DownloadService.downloadsDir().list();
        if (names != null) Collections.addAll(downloaded, names);
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
        java.io.InputStream in = c.getInputStream();
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
        refreshDownloaded();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
            boolean apk = Util.isApk(e.name);
            icon.setText(e.isDir ? "📁" : (video ? "🎬" : (apk ? "📦" : "📄")));
            name.setText(e.name);
            if (e.isDir) {
                sub.setText("папка");
            } else {
                StringBuilder sb = new StringBuilder(Util.humanSize(e.size));
                if (video && !Store.isWatched(BrowseActivity.this, e.fullPath)) {
                    long p = Store.getPos(BrowseActivity.this, e.fullPath);
                    if (p > 5000) sb.append("  ·  ⏱ ").append(Util.fmtTime(p));
                }
                if (downloaded.contains(e.name)) sb.append("  ·  ⬇");
                sub.setText(sb.toString());
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
