package com.tabletplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран файлового браузера. Загружает список каталога с сервера (/list),
 * позволяет заходить в папки, смотреть видео (libVLC) и скачивать файлы.
 * Навигация по папкам — через новые экземпляры этой же Activity, поэтому
 * системная кнопка "Назад" возвращает в родительскую папку.
 */
public class BrowseActivity extends Activity {
    private String base;
    private String path;
    private ListView list;
    private TextView header;
    private final List<Entry> entries = new ArrayList<Entry>();
    private ArrayAdapter<String> adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    static class Entry {
        final String name;
        final boolean isDir;
        final long size;

        Entry(String name, boolean isDir, long size) {
            this.name = name;
            this.isDir = isDir;
            this.size = size;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_browse);

        base = getIntent().getStringExtra("base");
        path = getIntent().getStringExtra("path");
        if (path == null) path = "";

        header = (TextView) findViewById(R.id.header);
        list = (ListView) findViewById(R.id.list);
        adapter = new ArrayAdapter<String>(this, R.layout.list_item, R.id.item_text);
        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                Entry e = entries.get(pos);
                if (e.isDir) {
                    Intent i = new Intent(BrowseActivity.this, BrowseActivity.class);
                    i.putExtra("base", base);
                    i.putExtra("path", join(path, e.name));
                    startActivity(i);
                } else {
                    onFileTap(e);
                }
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                Entry e = entries.get(pos);
                if (!e.isDir) confirmDownload(e);
                return true;
            }
        });

        loadList();
    }

    private void onFileTap(final Entry e) {
        final CharSequence[] opts = {"Смотреть", "Скачать на планшет"};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) play(e);
                        else confirmDownload(e);
                    }
                })
                .show();
    }

    private void play(Entry e) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", fileUrl(e.name));
        i.putExtra("title", e.name);
        startActivity(i);
    }

    private void confirmDownload(final Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("Скачать файл?")
                .setMessage(e.name + "\n\nБудет сохранён в папку Download.")
                .setPositiveButton("Скачать", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        download(e);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void loadList() {
        header.setText(path.isEmpty() ? "/" : "/" + path);
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    URL u = new URL(base + "/list?path=" + URLEncoder.encode(path, "UTF-8"));
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    int code = c.getResponseCode();
                    if (code != 200) {
                        fail("Ошибка сервера: " + code);
                        return;
                    }
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();

                    JSONObject o = new JSONObject(sb.toString());
                    JSONArray arr = o.getJSONArray("entries");
                    final List<Entry> tmp = new ArrayList<Entry>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject je = arr.getJSONObject(i);
                        tmp.add(new Entry(je.getString("name"), je.optBoolean("is_dir"), je.optLong("size")));
                    }

                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            entries.clear();
                            entries.addAll(tmp);
                            adapter.clear();
                            for (Entry e : entries) {
                                String icon = e.isDir ? "\uD83D\uDCC1 " : (isVideo(e.name) ? "\uD83C\uDFAC " : "\uD83D\uDCC4 ");
                                String label = icon + e.name + (e.isDir ? "" : "  (" + human(e.size) + ")");
                                adapter.add(label);
                            }
                            adapter.notifyDataSetChanged();
                        }
                    });
                } catch (Exception ex) {
                    fail("Не удалось подключиться: " + ex.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private void download(final Entry e) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMessage("Скачивание: " + e.name);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    URL u = new URL(fileUrl(e.name));
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    final int len = c.getContentLength();

                    InputStream in = new BufferedInputStream(c.getInputStream());
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, e.name);
                    FileOutputStream fos = new FileOutputStream(out);

                    byte[] buf = new byte[8192];
                    int n;
                    long totalRead = 0;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        totalRead += n;
                        if (len > 0) {
                            final int pct = (int) (totalRead * 100 / len);
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    pd.setProgress(pct);
                                }
                            });
                        }
                    }
                    fos.flush();
                    fos.close();
                    in.close();

                    final String p = out.getAbsolutePath();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Toast.makeText(BrowseActivity.this, "Сохранено: " + p, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception ex) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Toast.makeText(BrowseActivity.this, "Ошибка скачивания: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    // --- helpers ---

    private String join(String basePath, String name) {
        if (basePath == null || basePath.isEmpty()) return name;
        return basePath + "/" + name;
    }

    private String fileUrl(String name) {
        try {
            return base + "/download?path=" + URLEncoder.encode(join(path, name), "UTF-8");
        } catch (Exception ex) {
            return base + "/download?path=" + join(path, name);
        }
    }

    private void fail(final String m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(BrowseActivity.this, m, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static boolean isVideo(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".avi") || n.endsWith(".mov")
                || n.endsWith(".webm") || n.endsWith(".m4v") || n.endsWith(".flv") || n.endsWith(".ts")
                || n.endsWith(".wmv") || n.endsWith(".3gp") || n.endsWith(".mpg") || n.endsWith(".mpeg");
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }
}
