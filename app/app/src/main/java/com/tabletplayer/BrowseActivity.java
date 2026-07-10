package com.tabletplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrowseActivity extends Activity {

    private String base;
    private String path;
    private boolean searchMode = false;

    private ListView listView;
    private TextView header;
    private TextView empty;
    private EditText searchField;
    private EntryAdapter adapter;
    private final ArrayList<Entry> entries = new ArrayList<Entry>();

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    static class Entry {
        String name;
        boolean isDir;
        long size;
        String fullPath;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browse);

        base = getIntent().getStringExtra("base");
        path = getIntent().getStringExtra("path");
        if (path == null) path = "";

        listView = (ListView) findViewById(R.id.list);
        header = (TextView) findViewById(R.id.header);
        empty = (TextView) findViewById(R.id.empty);
        searchField = (EditText) findViewById(R.id.search);
        Button searchBtn = (Button) findViewById(R.id.search_btn);

        adapter = new EntryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                onEntryClick(entries.get(position));
            }
        });

        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSearch(); }
        });
        searchField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                doSearch();
                return true;
            }
        });

        header.setText(path.isEmpty() ? "/" : "/" + path);
        loadList();
    }

    private void onEntryClick(Entry e) {
        if (e.isDir) {
            Intent intent = new Intent(this, BrowseActivity.class);
            intent.putExtra("base", base);
            intent.putExtra("path", e.fullPath);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        } else {
            final Entry entry = e;
            new AlertDialog.Builder(this)
                    .setTitle(entry.name)
                    .setItems(new CharSequence[]{"Смотреть", "Скачать"}, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) play(entry);
                            else download(entry);
                        }
                    })
                    .show();
        }
    }

    private void play(Entry e) {
        String url = base + "/download?path=" + encode(e.fullPath);
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("title", e.name);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, android.R.anim.fade_out);
    }

    private void loadList() {
        searchMode = false;
        header.setText(path.isEmpty() ? "/" : "/" + path);
        final String url = base + "/list?path=" + encode(path);
        fetchInto(url, false);
    }

    private void doSearch() {
        String q = searchField.getText().toString().trim();
        hideKeyboard();
        if (TextUtils.isEmpty(q)) {
            loadList();
            return;
        }
        searchMode = true;
        header.setText("Поиск: " + q);
        final String url = base + "/search?q=" + encode(q);
        fetchInto(url, true);
    }

    private void fetchInto(final String url, final boolean isSearch) {
        empty.setVisibility(View.GONE);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Entry> result = new ArrayList<Entry>();
                String error = null;
                try {
                    String body = httpGet(url);
                    JSONObject obj = new JSONObject(body);
                    JSONArray arr = obj.getJSONArray("entries");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        Entry e = new Entry();
                        e.name = o.getString("name");
                        e.isDir = o.optBoolean("is_dir", false);
                        e.size = o.optLong("size", 0);
                        if (isSearch) {
                            e.fullPath = o.optString("path", e.name);
                        } else {
                            e.fullPath = path.isEmpty() ? e.name : path + "/" + e.name;
                        }
                        result.add(e);
                    }
                } catch (Exception ex) {
                    error = ex.getMessage();
                }
                final String err = error;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (err != null) {
                            Toast.makeText(BrowseActivity.this, "Ошибка: " + err, Toast.LENGTH_LONG).show();
                        }
                        entries.clear();
                        entries.addAll(result);
                        adapter.notifyDataSetChanged();
                        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                        animateList();
                    }
                });
            }
        });
    }

    private void download(final Entry e) {
        final String url = base + "/download?path=" + encode(e.fullPath);
        Toast.makeText(this, "Скачивание: " + e.name, Toast.LENGTH_SHORT).show();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, e.name);
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    InputStream in = new BufferedInputStream(conn.getInputStream());
                    FileOutputStream fos = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                    fos.flush();
                    fos.close();
                    in.close();
                    conn.disconnect();
                    msg = "Готово: " + out.getAbsolutePath();
                } catch (Exception ex) {
                    msg = "Ошибка скачивания: " + ex.getMessage();
                }
                final String fmsg = msg;
                main.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(BrowseActivity.this, fmsg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        InputStream in = new BufferedInputStream(conn.getInputStream());
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        conn.disconnect();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void animateList() {
        listView.setLayoutAnimation(
                android.view.animation.AnimationUtils.loadLayoutAnimation(this, R.anim.layout_anim));
        listView.scheduleLayoutAnimation();
    }

    @Override
    public void onBackPressed() {
        if (searchMode) {
            searchField.setText("");
            loadList();
        } else {
            super.onBackPressed();
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        }
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        String[] u = {"Б", "КБ", "МБ", "ГБ", "ТБ"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }

    class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(BrowseActivity.this).inflate(R.layout.list_item, parent, false);
            }
            Entry e = entries.get(position);
            TextView icon = (TextView) v.findViewById(R.id.item_icon);
            TextView name = (TextView) v.findViewById(R.id.item_name);
            TextView sub = (TextView) v.findViewById(R.id.item_sub);
            icon.setText(e.isDir ? "\uD83D\uDCC1" : "\uD83C\uDFAC");
            name.setText(e.name);
            if (searchMode) {
                sub.setText("/" + e.fullPath);
            } else if (e.isDir) {
                sub.setText("папка");
            } else {
                sub.setText(humanSize(e.size));
            }
            return v;
        }
    }
}
