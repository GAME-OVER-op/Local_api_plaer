package com.tabletplayer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Экран скачанных на устройство файлов: просмотр / установка / удаление. */
public class DownloadsActivity extends AppCompatActivity {
    private static final int MAX_FILES = 10000;
    private static final int MAX_DEPTH = 32;
    private final List<File> files = new ArrayList<>();
    private ListView list;
    private TextView empty;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_downloads);
        setTitle("Загрузки");
        list = findViewById(R.id.dl_list);
        empty = findViewById(R.id.dl_empty);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onClick(files.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(files.get(pos));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        files.clear();
        File dir = DownloadService.downloadsDir();
        try {
            collectFiles(dir, dir.getCanonicalPath(), 0, new HashSet<String>());
        } catch (Exception ignored) {
        }
        if (!files.isEmpty()) {
            Collections.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Util.naturalCompare(a.getName(), b.getName());
                }
            });
        }
        adapter.notifyDataSetChanged();
        empty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
    }


    private void collectFiles(File dir, String root, int depth, Set<String> visited) {
        if (depth > MAX_DEPTH || files.size() >= MAX_FILES) return;
        try {
            String canonical = dir.getCanonicalPath();
            if (!canonical.equals(root) && !canonical.startsWith(root + File.separator)) return;
            if (!visited.add(canonical)) return;
        } catch (Exception e) {
            return;
        }
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (files.size() >= MAX_FILES) return;
            if (f.isDirectory()) collectFiles(f, root, depth + 1, visited);
            else if (f.isFile() && !f.getName().endsWith(".part")) {
                try {
                    String canonical = f.getCanonicalPath();
                    if (canonical.startsWith(root + File.separator)) files.add(f);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void onClick(final File f) {
        final String name = f.getName();
        if (Util.isVideo(name)) {
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra("local", true);
            i.putExtra("path", f.getAbsolutePath());
            i.putExtra("name", name);
            startActivity(i);
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            return;
        }
        final String[] opts = Util.isApk(name)
                ? new String[]{"Установить", "Открыть", "Удалить"}
                : new String[]{"Открыть", "Удалить"};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(opts, (d, w) -> {
                    String o = opts[w];
                    if (o.equals("Установить")) view(f, "application/vnd.android.package-archive");
                    else if (o.equals("Открыть")) view(f, guessMime(name));
                    else confirmDelete(f);
                })
                .show();
    }

    private void view(File f, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(f), mime);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для открытия", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setMessage("Удалить файл с устройства?")
                .setPositiveButton("Удалить", (d, w) -> {
                    if (f.delete()) {
                        DownloadService.cleanupEmptyParents(f.getParentFile());
                        Toast.makeText(this, "Удалено", Toast.LENGTH_SHORT).show();
                        reload();
                    } else {
                        Toast.makeText(this, "Не удалось удалить", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private String guessMime(String name) {
        String n = name.toLowerCase();
        if (Util.isVideo(n)) return "video/*";
        if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac"))
            return "audio/*";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".webp"))
            return "image/*";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "*/*";
    }

    class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public Object getItem(int i) {
            return files.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = LayoutInflater.from(DownloadsActivity.this).inflate(R.layout.list_item, parent, false);
            }
            File f = files.get(pos);
            String nm = f.getName();
            TextView icon = convert.findViewById(R.id.item_icon);
            TextView name = convert.findViewById(R.id.item_name);
            TextView sub = convert.findViewById(R.id.item_sub);
            convert.findViewById(R.id.item_check).setVisibility(View.GONE);
            convert.findViewById(R.id.item_queue).setVisibility(View.GONE);
            icon.setText(Util.isVideo(nm) ? "🎬" : (Util.isApk(nm) ? "📦" : "📄"));
            name.setText(nm);
            String rel = DownloadService.relativeDisplayPath(f);
            int slash = rel.lastIndexOf(File.separatorChar);
            String where = slash > 0 ? rel.substring(0, slash) : "";
            sub.setText(where.isEmpty() ? Util.humanSize(f.length())
                    : Util.humanSize(f.length()) + "  ·  " + where);
            return convert;
        }
    }
}
