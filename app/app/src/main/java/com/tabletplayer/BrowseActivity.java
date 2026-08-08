package com.tabletplayer;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrowseActivity extends AppCompatActivity {
    private String base;
    private String path = "";
    private String serverName = "";
    private String serverId = "";
    private boolean ascending = true;
    private boolean searching = false;
    private String lastQuery = "";
    private boolean gridMode;
    private boolean drawerOpen;
    private boolean resumed;
    private long lastLatencyMs = -1;

    private ListView listView, bookmarksList, recentDirs, recentVideos;
    private GridView gridView;
    private EditText searchBox;
    private TextView empty, serverStatus, drawerTitle, infoName, infoPath, infoMeta, downloadBarText;
    private Button sortBtn, viewModeBtn, bookmarksBtn, infoPlay, infoDownload, infoLocation, toTop;
    private LinearLayout crumbs, skeleton, rightDrawer, bookmarksContent, infoContent;
    private HorizontalScrollView crumbScroll;
    private SwipeRefreshLayout swipe;
    private FrameLayout browserSurface;
    private View scrollThumb, downloadBar;
    private ProgressBar downloadBarProgress;

    private final List<Entry> entries = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FileAdapter adapter;
    private GridAdapter gridAdapter;
    private Entry infoEntry;

    private boolean queueMode = false;
    private final List<String> queuePaths = new ArrayList<>();
    private final List<String> queueNames = new ArrayList<>();
    private View queueBar;
    private TextView queueInfo;
    private Button queueBtn, queueClear, queuePlay;

    private View undoBar;
    private TextView undoText;
    private Button undoCancel;
    private final Runnable hideUndo = () -> undoBar.setVisibility(View.GONE);
    private final Runnable downloadTicker = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            updateDownloadUi();
            ui.postDelayed(this, 600);
        }
    };

    static class Entry {
        String name;
        boolean isDir;
        long size;
        String fullPath;
        long childCount;
        long directSize;
        boolean metaComplete = true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_browse);
        base = getIntent().getStringExtra("base");
        path = getIntent().getStringExtra("path");
        serverName = getIntent().getStringExtra("server_name");
        serverId = getIntent().getStringExtra("server_id");
        if (base == null) base = "";
        if (path == null) path = "";
        if (serverName == null) serverName = "";
        if (serverId == null) serverId = "";
        gridMode = UiStore.isGrid(this);

        bindViews();
        bindActions();
        adapter = new FileAdapter();
        gridAdapter = new GridAdapter();
        listView.setAdapter(adapter);
        gridView.setAdapter(gridAdapter);
        applyViewMode(false);
        updateSortLabel();
        updateServerStatus(true);
        updateQueueUi();
        buildSkeleton();
        loadList(path, 0);
    }

    private void bindViews() {
        listView = findViewById(R.id.list);
        gridView = findViewById(R.id.grid);
        searchBox = findViewById(R.id.search);
        empty = findViewById(R.id.empty);
        sortBtn = findViewById(R.id.sort_btn);
        viewModeBtn = findViewById(R.id.view_mode_btn);
        bookmarksBtn = findViewById(R.id.bookmarks_btn);
        serverStatus = findViewById(R.id.server_status);
        crumbs = findViewById(R.id.crumbs);
        crumbScroll = findViewById(R.id.crumb_scroll);
        swipe = findViewById(R.id.swipe);
        skeleton = findViewById(R.id.skeleton);
        browserSurface = findViewById(R.id.browser_surface);
        scrollThumb = findViewById(R.id.scroll_thumb);
        toTop = findViewById(R.id.to_top);
        rightDrawer = findViewById(R.id.right_drawer);
        drawerTitle = findViewById(R.id.drawer_title);
        bookmarksContent = findViewById(R.id.bookmarks_content);
        infoContent = findViewById(R.id.info_content);
        bookmarksList = findViewById(R.id.bookmarks_list);
        recentDirs = findViewById(R.id.recent_dirs);
        recentVideos = findViewById(R.id.recent_videos);
        infoName = findViewById(R.id.info_name);
        infoPath = findViewById(R.id.info_path);
        infoMeta = findViewById(R.id.info_meta);
        infoPlay = findViewById(R.id.info_play);
        infoDownload = findViewById(R.id.info_download);
        infoLocation = findViewById(R.id.info_location);
        downloadBar = findViewById(R.id.download_bar);
        downloadBarText = findViewById(R.id.download_bar_text);
        downloadBarProgress = findViewById(R.id.download_bar_progress);

        queueBar = findViewById(R.id.queue_bar);
        queueInfo = findViewById(R.id.queue_info);
        queueBtn = findViewById(R.id.queue_btn);
        queueClear = findViewById(R.id.queue_clear);
        queuePlay = findViewById(R.id.queue_play);
        undoBar = findViewById(R.id.undo_bar);
        undoText = findViewById(R.id.undo_text);
        undoCancel = findViewById(R.id.undo_cancel);
    }

    private void bindActions() {
        Button searchBtn = findViewById(R.id.search_btn);
        Button serversBtn = findViewById(R.id.servers_btn);
        ImageButton downloadsBtn = findViewById(R.id.downloads_btn);
        Button drawerClose = findViewById(R.id.drawer_close);

        downloadsBtn.setOnClickListener(v -> openDownloads());
        downloadBar.setOnClickListener(v -> openDownloads());
        serversBtn.setOnClickListener(v -> { finish(); overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right); });
        serverStatus.setOnClickListener(v -> showServerInfo());
        drawerClose.setOnClickListener(v -> closeDrawer());
        bookmarksBtn.setOnClickListener(v -> {
            if (drawerOpen && bookmarksContent.getVisibility() == View.VISIBLE) closeDrawer(); else showBookmarksDrawer();
        });
        viewModeBtn.setOnClickListener(v -> { gridMode = !gridMode; UiStore.setGrid(this, gridMode); applyViewMode(true); });
        toTop.setOnClickListener(v -> {
            if (gridMode) gridView.smoothScrollToPosition(0); else listView.smoothScrollToPosition(0);
        });

        queueBtn.setOnClickListener(v -> { queueMode = !queueMode; updateQueueUi(); notifyAdapters(); });
        queueClear.setOnClickListener(v -> { queuePaths.clear(); queueNames.clear(); updateQueueUi(); notifyAdapters(); });
        queuePlay.setOnClickListener(v -> playQueue());

        sortBtn.setOnClickListener(v -> { ascending = !ascending; updateSortLabel(); resortAndShow(0); });
        searchBtn.setOnClickListener(v -> runSearch());
        searchBox.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); return true; }
            return false;
        });
        swipe.setOnRefreshListener(() -> { if (searching) doSearch(lastQuery); else loadList(path, 0); });
        swipe.setOnChildScrollUpCallback((parent, child) -> gridMode ? gridView.canScrollVertically(-1) : listView.canScrollVertically(-1));

        listView.setOnItemClickListener((parent, view, pos, id) -> onItemClick(entries.get(pos)));
        listView.setOnItemLongClickListener((parent, view, pos, id) -> { showItemMenu(entries.get(pos)); return true; });
        gridView.setOnItemClickListener((parent, view, pos, id) -> onItemClick(entries.get(pos)));
        gridView.setOnItemLongClickListener((parent, view, pos, id) -> { showItemMenu(entries.get(pos)); return true; });
        AbsListView.OnScrollListener scroll = new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {}
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { updateScrollUi(first, visible, total); }
        };
        listView.setOnScrollListener(scroll);
        gridView.setOnScrollListener(scroll);
    }

    private void openDownloads() {
        startActivity(new Intent(this, DownloadsActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void setCrumbs(String p) {
        crumbs.removeAllViews();
        crumbs.addView(makeCrumb("⌂", ""));
        if (p != null && !p.isEmpty()) {
            String[] parts = p.split("/");
            StringBuilder acc = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (acc.length() > 0) acc.append('/');
                acc.append(part);
                crumbs.addView(makeSep());
                crumbs.addView(makeCrumb(part, acc.toString()));
            }
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void setSearchCrumb(String q) {
        crumbs.removeAllViews();
        TextView t = makeCrumb("Поиск: " + q, path);
        t.setOnClickListener(v -> { searchBox.setText(""); searching = false; loadList(path, -1); });
        crumbs.addView(t);
    }

    private TextView makeCrumb(String label, final String target) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(getResources().getColor(R.color.text_primary));
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(13), 0, dp(13), 0);
        t.setMinWidth(dp(48));
        t.setMinHeight(dp(40));
        t.setSingleLine(true);
        t.setBackgroundResource(R.drawable.crumb_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(3), 0, dp(3), 0); t.setLayoutParams(lp);
        t.setOnClickListener(v -> { searchBox.setText(""); int dir = depth(target) < depth(path) ? -1 : 1; loadList(target, dir); });
        return t;
    }

    private TextView makeSep() {
        TextView t = new TextView(this);
        t.setText("›"); t.setTextColor(getResources().getColor(R.color.text_secondary)); t.setTextSize(18); t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(18), dp(40)));
        return t;
    }

    private int depth(String p) {
        if (p == null || p.isEmpty()) return 0;
        int n = 1; for (int i = 0; i < p.length(); i++) if (p.charAt(i) == '/') n++; return n;
    }

    private void updateSortLabel() { sortBtn.setText(ascending ? "A↑" : "A↓"); }

    private void runSearch() {
        String q = searchBox.getText().toString().trim();
        if (q.isEmpty()) { searching = false; loadList(path, 0); return; }
        doSearch(q);
    }

    private void loadList(final String p, final int direction) {
        searching = false;
        setCrumbs(p);
        showLoading(true);
        io.execute(() -> {
            try {
                long requestStarted = System.currentTimeMillis();
                JSONObject o = new JSONObject(httpGet(base + "/list?path=" + Util.enc(p)));
                final long latency = System.currentTimeMillis() - requestStarted;
                JSONArray arr = o.getJSONArray("entries");
                final List<Entry> loaded = parseEntries(arr, p, false);
                final String newServerId = o.optString("server_id", serverId);
                final String newServerName = o.optString("server_name", serverName);
                final int serverPort = o.optInt("port", basePort());
                ui.post(() -> {
                    serverId = newServerId == null ? "" : newServerId;
                    serverName = newServerName == null ? "" : newServerName;
                    if (!serverId.isEmpty()) UiStore.saveServer(this, serverId, serverName, baseHost(), serverPort);
                    path = p;
                    lastLatencyMs = latency;
                    UiStore.addRecentDir(this, serverId, base, path);
                    entries.clear(); entries.addAll(loaded);
                    setCrumbs(path);
                    updateServerStatus(true);
                    resortAndShow(direction);
                    showLoading(false);
                    if (drawerOpen && bookmarksContent.getVisibility() == View.VISIBLE) renderDrawerLists();
                    scrollToCurrentFile();
                });
            } catch (Exception ex) { showError(ex); }
        });
    }

    private List<Entry> parseEntries(JSONArray arr, String parent, boolean searchResults) throws Exception {
        List<Entry> loaded = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            Entry en = new Entry();
            en.name = e.getString("name"); en.isDir = e.getBoolean("is_dir"); en.size = e.optLong("size", 0);
            en.fullPath = searchResults ? e.getString("path") : (parent.isEmpty() ? en.name : parent + "/" + en.name);
            en.childCount = e.optLong("child_count", -1); en.directSize = e.optLong("direct_size", 0); en.metaComplete = e.optBoolean("meta_complete", true);
            loaded.add(en);
        }
        return loaded;
    }

    private void doSearch(final String q) {
        lastQuery = q; searching = true; setSearchCrumb(q); showLoading(true);
        io.execute(() -> {
            try {
                long requestStarted = System.currentTimeMillis();
                JSONObject o = new JSONObject(httpGet(base + "/search?q=" + Util.enc(q) + "&path=" + Util.enc(path)));
                final long latency = System.currentTimeMillis() - requestStarted;
                final List<Entry> loaded = parseEntries(o.getJSONArray("entries"), path, true);
                ui.post(() -> {
                    entries.clear(); entries.addAll(loaded); lastLatencyMs = latency; setSearchCrumb(q); updateServerStatus(true);
                    resortAndShow(1); showLoading(false);
                });
            } catch (Exception ex) { showError(ex); }
        });
    }

    private void showError(final Exception ex) {
        ui.post(() -> {
            showLoading(false); swipe.setRefreshing(false); updateServerStatus(false);
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (msg != null && msg.contains("403")) msg = "Доступ отклонён сервером";
            Toast.makeText(this, "Ошибка: " + msg, Toast.LENGTH_LONG).show();
        });
    }

    private void resortAndShow(int direction) {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                int c = Util.naturalCompare(a.name, b.name); return ascending ? c : -c;
            }
        });
        notifyAdapters();
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        if (direction != 0) animateContentIn(direction);
    }

    private void animateContentIn(int direction) {
        View v = gridMode ? gridView : listView;
        v.setTranslationX(direction > 0 ? dp(42) : -dp(42));
        v.setAlpha(0.72f);
        v.animate().translationX(0).alpha(1f).setDuration(180).start();
    }

    private void onItemClick(Entry e) {
        if (queueMode && !e.isDir && Util.isVideo(e.name)) { toggleQueue(e); return; }
        if (e.isDir) {
            searchBox.setText("");
            loadList(e.fullPath, 1);
        } else {
            showInfoDrawer(e);
        }
    }

    private void showItemMenu(final Entry e) {
        if (e.isDir) {
            boolean saved = UiStore.isBookmarked(this, serverId, base, e.fullPath);
            final String[] opts = new String[]{"Открыть", saved ? "Удалить из закладок" : "Добавить в закладки"};
            new AlertDialog.Builder(this).setTitle(e.name).setItems(opts, (d, w) -> {
                if (w == 0) loadList(e.fullPath, 1);
                else {
                    UiStore.toggleBookmark(this, serverId, base, e.fullPath, e.name);
                    Toast.makeText(this, saved ? "Закладка удалена" : "Добавлено в закладки", Toast.LENGTH_SHORT).show();
                    if (drawerOpen) renderDrawerLists();
                }
            }).show();
            return;
        }
        final boolean video = Util.isVideo(e.name), apk = Util.isApk(e.name);
        final List<String> opts = new ArrayList<>();
        opts.add("Информация"); if (video) opts.add("Смотреть"); opts.add("Скачать"); if (apk) opts.add("Скачать и установить");
        if (searching) opts.add("Открыть расположение");
        final String[] arr = opts.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(e.name).setItems(arr, (d, w) -> {
            String o = arr[w];
            if (o.equals("Информация")) showInfoDrawer(e);
            else if (o.equals("Смотреть")) openPlayer(e);
            else if (o.equals("Скачать")) startDownload(e, false);
            else if (o.equals("Скачать и установить")) startDownload(e, true);
            else openLocation(e);
        }).show();
    }

    private void showInfoDrawer(Entry e) {
        infoEntry = e;
        drawerTitle.setText("Информация");
        bookmarksContent.setVisibility(View.GONE); infoContent.setVisibility(View.VISIBLE);
        refreshInfoPanel();
        showDrawerInternal(false);
    }

    private void refreshInfoPanel() {
        final Entry e = infoEntry;
        if (e == null) return;
        infoName.setText(e.name); infoPath.setText(e.fullPath);
        StringBuilder meta = new StringBuilder();
        if (e.isDir) meta.append(folderMeta(e)); else meta.append(Util.humanSize(e.size));
        if (!e.isDir && Util.isVideo(e.name)) {
            if (Store.isWatched(this, e.fullPath)) meta.append("\nПросмотрено");
            else { long pos = Store.getPos(this, e.fullPath); if (pos > 5000) meta.append("\nПродолжить с ").append(Util.fmtTime(pos)); }
        }
        DownloadService.Progress p = DownloadService.progressFor(base, e.fullPath);
        if (p != null) meta.append("\nЗагрузка: ").append(p.percent() >= 0 ? p.percent() + "%" : Util.humanSize(p.done));
        infoMeta.setText(meta.toString());
        infoPlay.setVisibility(!e.isDir && Util.isVideo(e.name) ? View.VISIBLE : View.GONE);
        infoDownload.setVisibility(e.isDir ? View.GONE : View.VISIBLE);
        infoLocation.setVisibility(searching ? View.VISIBLE : View.GONE);
        infoPlay.setOnClickListener(v -> openPlayer(e));
        infoDownload.setOnClickListener(v -> startDownload(e, false));
        infoLocation.setOnClickListener(v -> openLocation(e));
    }

    private void openLocation(Entry e) {
        String parent = parentPath(e.fullPath);
        closeDrawer(); searchBox.setText(""); searching = false; loadList(parent, -1);
    }

    private void showBookmarksDrawer() {
        drawerTitle.setText("Закладки"); infoContent.setVisibility(View.GONE); bookmarksContent.setVisibility(View.VISIBLE);
        renderDrawerLists(); showDrawerInternal(true);
    }

    private void renderDrawerLists() {
        final List<UiStore.PathItem> bm = UiStore.bookmarks(this, serverId, base);
        final List<UiStore.PathItem> rd = UiStore.recentDirs(this, serverId, base);
        final List<UiStore.PathItem> rv = UiStore.recentVideos(this, serverId, base);
        bookmarksList.setAdapter(new PathAdapter(bm)); recentDirs.setAdapter(new PathAdapter(rd)); recentVideos.setAdapter(new PathAdapter(rv));
        bookmarksList.setOnItemClickListener((p, v, pos, id) -> { closeDrawer(); loadList(bm.get(pos).path, depth(bm.get(pos).path) >= depth(path) ? 1 : -1); });
        bookmarksList.setOnItemLongClickListener((p, v, pos, id) -> {
            UiStore.PathItem x = bm.get(pos); UiStore.toggleBookmark(this, serverId, base, x.path, x.name); renderDrawerLists(); return true;
        });
        recentDirs.setOnItemClickListener((p, v, pos, id) -> { closeDrawer(); loadList(rd.get(pos).path, 0); });
        recentVideos.setOnItemClickListener((p, v, pos, id) -> {
            UiStore.PathItem x = rv.get(pos); Entry e = new Entry(); e.name = x.name; e.fullPath = x.path; e.isDir = false; openPlayer(e);
        });
    }

    private void showDrawerInternal(boolean bookmarks) {
        int screen = getResources().getDisplayMetrics().widthPixels;
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        int width = wide ? Math.min(dp(360), (int)(screen * 0.42f)) : Math.min(dp(330), (int)(screen * 0.84f));
        FrameLayout.LayoutParams dpLp = (FrameLayout.LayoutParams) rightDrawer.getLayoutParams(); dpLp.width = width; rightDrawer.setLayoutParams(dpLp);
        if (wide) {
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) browserSurface.getLayoutParams(); bp.rightMargin = width; browserSurface.setLayoutParams(bp);
        }
        rightDrawer.setVisibility(View.VISIBLE); rightDrawer.setTranslationX(width); rightDrawer.animate().translationX(0).setDuration(220).start();
        drawerOpen = true; animateBookmarkButton(true);
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        final int width = rightDrawer.getWidth() > 0 ? rightDrawer.getWidth() : dp(320);
        rightDrawer.animate().translationX(width).setDuration(200).withEndAction(() -> {
            rightDrawer.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) browserSurface.getLayoutParams(); bp.rightMargin = 0; browserSurface.setLayoutParams(bp);
        }).start();
        drawerOpen = false; infoEntry = null; animateBookmarkButton(false);
    }

    private void animateBookmarkButton(final boolean open) {
        bookmarksBtn.animate().scaleX(0.72f).scaleY(0.72f).setDuration(90).withEndAction(() -> {
            bookmarksBtn.setText(open ? "★" : "☆");
            bookmarksBtn.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        }).start();
    }

    private void toggleQueue(Entry e) {
        int idx = queuePaths.indexOf(e.fullPath);
        if (idx >= 0) { queuePaths.remove(idx); queueNames.remove(idx); }
        else { queuePaths.add(e.fullPath); queueNames.add(e.name); }
        updateQueueUi(); notifyAdapters();
    }

    private void updateQueueUi() {
        if (queueBtn != null) queueBtn.setText(queueMode ? "Очередь ✓" : "Очередь");
        if (queueBar != null) queueBar.setVisibility(queueMode ? View.VISIBLE : View.GONE);
        if (queueInfo != null) queueInfo.setText("В очереди: " + queuePaths.size());
    }

    private void playQueue() {
        if (queuePaths.isEmpty()) { Toast.makeText(this, "Очередь пуста — отметьте видео по порядку", Toast.LENGTH_SHORT).show(); return; }
        Intent i = playerIntent(queuePaths.get(0), queueNames.get(0));
        i.putExtra("folder", ""); i.putExtra("queue_paths", queuePaths.toArray(new String[0])); i.putExtra("queue_names", queueNames.toArray(new String[0]));
        UiStore.setLastPlayed(this, serverId, base, queuePaths.get(0));
        startActivity(i); overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void openPlayer(Entry e) {
        UiStore.setLastPlayed(this, serverId, base, e.fullPath);
        UiStore.addRecentVideo(this, serverId, base, e.fullPath, e.name);
        Intent i = playerIntent(e.fullPath, e.name); i.putExtra("folder", parentPath(e.fullPath));
        startActivity(i); overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private Intent playerIntent(String videoPath, String name) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("base", base); i.putExtra("path", videoPath); i.putExtra("name", name);
        i.putExtra("server_name", serverName); i.putExtra("server_id", serverId); return i;
    }

    private void startDownload(final Entry e, boolean install) {
        final int id = DownloadService.nextId();
        Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_START)
                .putExtra("id", id).putExtra("base", base).putExtra("path", e.fullPath).putExtra("name", e.name).putExtra("install", install);
        startService(i); showUndo(e.name, id); ui.postDelayed(this::updateDownloadUi, 120);
    }

    private void showUndo(String name, final int id) {
        undoText.setText("Загрузка: " + name); undoBar.setVisibility(View.VISIBLE);
        undoCancel.setOnClickListener(v -> {
            Intent i = new Intent(this, DownloadService.class).setAction(DownloadService.ACTION_CANCEL).putExtra("id", id);
            startService(i); undoBar.setVisibility(View.GONE); Toast.makeText(this, "Отменено", Toast.LENGTH_SHORT).show();
        });
        ui.removeCallbacks(hideUndo); ui.postDelayed(hideUndo, 5000);
    }

    private void updateDownloadUi() {
        List<DownloadService.Progress> all = DownloadService.activeProgress();
        int count = 0; long done = 0, total = 0, speed = 0; boolean allKnown = true;
        for (DownloadService.Progress p : all) {
            if (!base.equals(p.base)) continue;
            count++; done += p.done; speed += p.bytesPerSec;
            if (p.total > 0) total += p.total; else allKnown = false;
        }
        if (count == 0) downloadBar.setVisibility(View.GONE);
        else {
            downloadBar.setVisibility(View.VISIBLE);
            int pct = allKnown && total > 0 ? (int)Math.min(100, done * 100L / total) : -1;
            String text = "↓ " + count + " " + pluralDownloads(count);
            if (speed > 0) text += " · " + Util.humanSize(speed) + "/с";
            if (pct >= 0) text += " · " + pct + "%";
            downloadBarText.setText(text); downloadBarProgress.setIndeterminate(pct < 0); if (pct >= 0) downloadBarProgress.setProgress(pct);
        }
        notifyAdapters();
        if (infoEntry != null && infoContent.getVisibility() == View.VISIBLE) refreshInfoPanel();
    }

    private String pluralDownloads(int n) { return n == 1 ? "загрузка" : (n >= 2 && n <= 4 ? "загрузки" : "загрузок"); }

    private void applyViewMode(boolean animate) {
        listView.setVisibility(gridMode ? View.GONE : View.VISIBLE); gridView.setVisibility(gridMode ? View.VISIBLE : View.GONE);
        viewModeBtn.setText(gridMode ? "☰" : "▦");
        if (animate) { View v = gridMode ? gridView : listView; v.setAlpha(0.3f); v.animate().alpha(1f).setDuration(160).start(); }
        updateScrollUi(0, 0, entries.size());
    }

    private void updateScrollUi(int first, int visible, int total) {
        toTop.setVisibility(first > 8 ? View.VISIBLE : View.GONE);
        if (total <= visible || total <= 0) { scrollThumb.setVisibility(View.GONE); return; }
        scrollThumb.setVisibility(View.VISIBLE);
        int h = browserSurface.getHeight(); if (h <= 0) return;
        int thumbH = Math.max(dp(36), (int)(h * Math.min(1f, visible / (float)total)));
        ViewGroup.LayoutParams lp = scrollThumb.getLayoutParams(); lp.height = thumbH; scrollThumb.setLayoutParams(lp);
        float maxY = Math.max(0, h - thumbH); float ratio = first / (float)Math.max(1, total - visible); scrollThumb.setY(maxY * ratio);
    }

    private void buildSkeleton() {
        if (skeleton.getChildCount() > 0) return;
        for (int i = 0; i < 7; i++) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            View icon = new View(this); icon.setBackgroundResource(R.drawable.skeleton_bg); row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout lines = new LinearLayout(this); lines.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams linesLp = new LinearLayout.LayoutParams(0, dp(42), 1f); linesLp.setMargins(dp(12), 0, dp(24), 0); row.addView(lines, linesLp);
            View a = new View(this); a.setBackgroundResource(R.drawable.skeleton_bg); LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(13)); aLp.setMargins(0, dp(3), 0, 0); lines.addView(a, aLp);
            View b = new View(this); b.setBackgroundResource(R.drawable.skeleton_bg); LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(120), dp(10)); bLp.setMargins(0, dp(8), 0, 0); lines.addView(b, bLp);
            skeleton.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        }
    }

    private void showLoading(boolean on) {
        swipe.setRefreshing(false);
        skeleton.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) { empty.setVisibility(View.GONE); scrollThumb.setVisibility(View.GONE); }
    }

    private void updateServerStatus(boolean online) {
        String n = serverName == null || serverName.trim().isEmpty() ? "media-server" : serverName;
        String latency = online && lastLatencyMs >= 0 ? " · " + lastLatencyMs + " ms" : "";
        serverStatus.setText((online ? "● " : "◌ ") + n + latency);
    }

    private void showServerInfo() {
        String n = serverName == null || serverName.isEmpty() ? "media-server" : serverName;
        String msg = "Адрес: " + base.replace("http://", "") + (serverId.isEmpty() ? "" : "\nID: " + serverId);
        new AlertDialog.Builder(this).setTitle(n).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void scrollToCurrentFile() {
        final String current = UiStore.lastPlayed(this, serverId, base);
        if (current == null || current.isEmpty()) return;
        int found = -1; for (int i = 0; i < entries.size(); i++) if (current.equals(entries.get(i).fullPath)) { found = i; break; }
        if (found < 0) return;
        final int pos = found;
        ui.postDelayed(() -> {
            if (gridMode) gridView.setSelection(Math.max(0, pos - 2)); else listView.setSelection(Math.max(0, pos - 3));
            notifyAdapters();
        }, 100);
    }

    private String folderMeta(Entry e) {
        if (e.childCount < 0) return "Папка";
        String count = (e.metaComplete ? "" : "≥") + e.childCount + " элементов";
        return e.directSize > 0 ? count + " · " + Util.humanSize(e.directSize) : count;
    }

    private String entrySub(Entry e) {
        if (e.isDir) return folderMeta(e);
        StringBuilder sb = new StringBuilder(Util.humanSize(e.size));
        boolean video = Util.isVideo(e.name);
        if (video && !Store.isWatched(this, e.fullPath)) { long p = Store.getPos(this, e.fullPath); if (p > 5000) sb.append(" · ").append(Util.fmtTime(p)); }
        DownloadService.Progress dp = DownloadService.progressFor(base, e.fullPath);
        if (dp != null) sb.append(" · загрузка ").append(dp.percent() >= 0 ? dp.percent() + "%" : Util.humanSize(dp.done));
        else if (DownloadService.isDownloaded(base, e.fullPath, e.name)) sb.append(" · локально");
        if (searching) sb.append(" · ").append(parentPath(e.fullPath));
        return sb.toString();
    }

    private String iconFor(Entry e) { return e.isDir ? "📁" : (Util.isVideo(e.name) ? "🎬" : (Util.isApk(e.name) ? "📦" : "📄")); }

    private void notifyAdapters() { if (adapter != null) adapter.notifyDataSetChanged(); if (gridAdapter != null) gridAdapter.notifyDataSetChanged(); }

    private String parentPath(String p) { if (p == null) return ""; int idx = p.lastIndexOf('/'); return idx >= 0 ? p.substring(0, idx) : ""; }
    private String baseHost() { try { return new URL(base).getHost(); } catch (Exception e) { return ""; } }
    private int basePort() { try { int p = new URL(base).getPort(); return p > 0 ? p : 10930; } catch (Exception e) { return 10930; } }

    private String httpGet(String u) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpURLConnection c = null; java.io.InputStream in = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection(); App.auth(c, this); c.setUseCaches(false); c.setRequestProperty("Connection", "close");
                c.setConnectTimeout(attempt == 1 ? 5000 : 8000); c.setReadTimeout(attempt == 1 ? 12000 : 20000);
                int code = c.getResponseCode(); if (code != 200) throw new RuntimeException("HTTP " + code);
                in = c.getInputStream(); java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int r;
                while ((r = in.read(buf)) != -1) bo.write(buf, 0, r); return bo.toString("UTF-8");
            } catch (IOException e) {
                last = e; try { Thread.sleep(350L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
            } finally { if (in != null) try { in.close(); } catch (Exception ignored) {} if (c != null) try { c.disconnect(); } catch (Exception ignored) {} }
        }
        throw last == null ? new SocketTimeoutException("server timeout") : last;
    }

    @Override public void onBackPressed() {
        if (drawerOpen) { closeDrawer(); return; }
        if (searching) { searchBox.setText(""); searching = false; loadList(path, -1); return; }
        if (path != null && !path.isEmpty()) { loadList(parentPath(path), -1); return; }
        super.onBackPressed(); overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true; ui.removeCallbacks(downloadTicker); ui.post(downloadTicker); ui.postDelayed(this::scrollToCurrentFile, 180);
    }
    @Override protected void onPause() { resumed = false; ui.removeCallbacks(downloadTicker); super.onPause(); }
    @Override protected void onDestroy() { resumed = false; ui.removeCallbacksAndMessages(null); io.shutdownNow(); super.onDestroy(); }

    class FileAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            if (convert == null) convert = LayoutInflater.from(BrowseActivity.this).inflate(R.layout.list_item, parent, false);
            Entry e = entries.get(pos);
            TextView icon = convert.findViewById(R.id.item_icon), name = convert.findViewById(R.id.item_name), sub = convert.findViewById(R.id.item_sub), check = convert.findViewById(R.id.item_check), queueNum = convert.findViewById(R.id.item_queue);
            ProgressBar progress = convert.findViewById(R.id.item_progress); View accent = convert.findViewById(R.id.item_accent);
            icon.setText(iconFor(e)); name.setText(e.name); sub.setText(entrySub(e));
            boolean video = !e.isDir && Util.isVideo(e.name); check.setVisibility(video && Store.isWatched(BrowseActivity.this, e.fullPath) ? View.VISIBLE : View.GONE);
            DownloadService.Progress dp = DownloadService.progressFor(base, e.fullPath);
            if (dp != null) { progress.setVisibility(View.VISIBLE); int pct = dp.percent(); progress.setIndeterminate(pct < 0); if (pct >= 0) progress.setProgress(pct); } else progress.setVisibility(View.GONE);
            String current = UiStore.lastPlayed(BrowseActivity.this, serverId, base); accent.setVisibility(video && e.fullPath.equals(current) ? View.VISIBLE : View.GONE);
            if (queueMode && video) { int qi = queuePaths.indexOf(e.fullPath); if (qi >= 0) { queueNum.setText(String.valueOf(qi + 1)); queueNum.setVisibility(View.VISIBLE); } else queueNum.setVisibility(View.GONE); } else queueNum.setVisibility(View.GONE);
            return convert;
        }
    }

    class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            if (convert == null) convert = LayoutInflater.from(BrowseActivity.this).inflate(R.layout.grid_item, parent, false);
            Entry e = entries.get(pos); TextView icon = convert.findViewById(R.id.grid_icon), name = convert.findViewById(R.id.grid_name), sub = convert.findViewById(R.id.grid_sub);
            icon.setText(iconFor(e)); name.setText(e.name);
            String s = e.isDir ? folderMeta(e) : Util.humanSize(e.size);
            DownloadService.Progress p = DownloadService.progressFor(base, e.fullPath); if (p != null && p.percent() >= 0) s = "Загрузка " + p.percent() + "%";
            if (queueMode && !e.isDir && Util.isVideo(e.name)) { int q = queuePaths.indexOf(e.fullPath); if (q >= 0) s = "Очередь " + (q + 1); }
            sub.setText(s); return convert;
        }
    }

    class PathAdapter extends ArrayAdapter<UiStore.PathItem> {
        private final List<UiStore.PathItem> data;
        PathAdapter(List<UiStore.PathItem> data) { super(BrowseActivity.this, android.R.layout.simple_list_item_2, android.R.id.text1, data); this.data = data; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View v = super.getView(position, convertView, parent); TextView a = v.findViewById(android.R.id.text1), b = v.findViewById(android.R.id.text2); UiStore.PathItem x = data.get(position);
            a.setText(x.name); a.setTextColor(getResources().getColor(R.color.text_primary)); a.setTextSize(14); b.setText(x.path.isEmpty() ? "Домой" : x.path); b.setTextColor(getResources().getColor(R.color.text_secondary)); b.setTextSize(11); return v;
        }
    }
}
