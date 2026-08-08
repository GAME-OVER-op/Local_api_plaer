package com.tabletplayer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** UI state that belongs to a concrete media server. */
public final class UiStore {
    private static final String KEY_SERVERS = "trusted_servers_v2";
    private static final String KEY_BOOKMARKS = "bookmarks_v2";
    private static final String KEY_RECENT_DIRS = "recent_dirs_v2";
    private static final String KEY_RECENT_VIDEOS = "recent_videos_v2";
    private static final String KEY_GRID = "browse_grid";
    private static final String KEY_LAST_PLAYED = "last_played_v2";
    private static final int MAX_RECENT = 12;

    private UiStore() {}

    public static class ServerRecord {
        public String id = "";
        public String name = "media-server";
        public String host = "";
        public int port = 10930;
    }

    public static class PathItem {
        public String name = "";
        public String path = "";
    }

    public static synchronized void saveServer(Context c, String id, String name, String host, int port) {
        if (id == null || id.trim().isEmpty() || host == null || host.trim().isEmpty()) return;
        try {
            JSONArray in = new JSONArray(App.prefs(c).getString(KEY_SERVERS, "[]"));
            JSONArray out = new JSONArray();
            JSONObject first = new JSONObject();
            first.put("id", id);
            first.put("name", cleanName(name));
            first.put("host", host);
            first.put("port", port);
            out.put(first);
            for (int i = 0; i < in.length() && out.length() < 12; i++) {
                JSONObject o = in.optJSONObject(i);
                if (o == null || id.equals(o.optString("id"))) continue;
                out.put(o);
            }
            App.prefs(c).edit().putString(KEY_SERVERS, out.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized List<ServerRecord> servers(Context c) {
        List<ServerRecord> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(App.prefs(c).getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                ServerRecord r = new ServerRecord();
                r.id = o.optString("id", "");
                r.name = cleanName(o.optString("name", "media-server"));
                r.host = o.optString("host", "");
                r.port = o.optInt("port", 10930);
                if (!r.id.isEmpty() && !r.host.isEmpty()) list.add(r);
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static synchronized ServerRecord server(Context c, String id) {
        if (id == null) return null;
        for (ServerRecord r : servers(c)) if (id.equals(r.id)) return r;
        return null;
    }

    private static String bucket(String serverId, String fallbackBase) {
        String s = serverId == null ? "" : serverId.trim();
        if (!s.isEmpty()) return s;
        return "legacy:" + (fallbackBase == null ? "" : fallbackBase);
    }

    public static synchronized List<PathItem> bookmarks(Context c, String serverId, String base) {
        return getPathList(c, KEY_BOOKMARKS, bucket(serverId, base));
    }

    public static synchronized boolean isBookmarked(Context c, String serverId, String base, String path) {
        for (PathItem p : bookmarks(c, serverId, base)) if (p.path.equals(path)) return true;
        return false;
    }

    public static synchronized void toggleBookmark(Context c, String serverId, String base, String path, String name) {
        String b = bucket(serverId, base);
        List<PathItem> list = getPathList(c, KEY_BOOKMARKS, b);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).path.equals(path)) {
                list.remove(i);
                putPathList(c, KEY_BOOKMARKS, b, list, 50);
                return;
            }
        }
        PathItem n = new PathItem();
        n.path = path == null ? "" : path;
        n.name = cleanPathName(name, path);
        list.add(0, n);
        putPathList(c, KEY_BOOKMARKS, b, list, 50);
    }

    public static synchronized void addRecentDir(Context c, String serverId, String base, String path) {
        if (path == null) path = "";
        addRecent(c, KEY_RECENT_DIRS, bucket(serverId, base), path, pathName(path));
    }

    public static synchronized List<PathItem> recentDirs(Context c, String serverId, String base) {
        return getPathList(c, KEY_RECENT_DIRS, bucket(serverId, base));
    }

    public static synchronized void addRecentVideo(Context c, String serverId, String base, String path, String name) {
        if (path == null || path.isEmpty()) return;
        addRecent(c, KEY_RECENT_VIDEOS, bucket(serverId, base), path, cleanPathName(name, path));
    }

    public static synchronized List<PathItem> recentVideos(Context c, String serverId, String base) {
        return getPathList(c, KEY_RECENT_VIDEOS, bucket(serverId, base));
    }

    private static void addRecent(Context c, String key, String bucket, String path, String name) {
        List<PathItem> list = getPathList(c, key, bucket);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).path.equals(path)) list.remove(i);
        PathItem n = new PathItem(); n.path = path; n.name = name;
        list.add(0, n);
        putPathList(c, key, bucket, list, MAX_RECENT);
    }

    private static List<PathItem> getPathList(Context c, String key, String bucket) {
        List<PathItem> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(App.prefs(c).getString(key, "{}"));
            JSONArray a = root.optJSONArray(bucket);
            if (a == null) return list;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                PathItem p = new PathItem();
                p.path = o.optString("path", "");
                p.name = cleanPathName(o.optString("name", ""), p.path);
                list.add(p);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static void putPathList(Context c, String key, String bucket, List<PathItem> list, int max) {
        try {
            JSONObject root = new JSONObject(App.prefs(c).getString(key, "{}"));
            JSONArray a = new JSONArray();
            for (int i = 0; i < list.size() && i < max; i++) {
                PathItem p = list.get(i);
                JSONObject o = new JSONObject();
                o.put("path", p.path);
                o.put("name", p.name);
                a.put(o);
            }
            root.put(bucket, a);
            App.prefs(c).edit().putString(key, root.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static boolean isGrid(Context c) { return App.prefs(c).getBoolean(KEY_GRID, false); }
    public static void setGrid(Context c, boolean grid) { App.prefs(c).edit().putBoolean(KEY_GRID, grid).apply(); }

    public static void setLastPlayed(Context c, String serverId, String base, String path) {
        try {
            JSONObject o = new JSONObject(App.prefs(c).getString(KEY_LAST_PLAYED, "{}"));
            o.put(bucket(serverId, base), path == null ? "" : path);
            App.prefs(c).edit().putString(KEY_LAST_PLAYED, o.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static String lastPlayed(Context c, String serverId, String base) {
        try {
            return new JSONObject(App.prefs(c).getString(KEY_LAST_PLAYED, "{}"))
                    .optString(bucket(serverId, base), "");
        } catch (Exception e) { return ""; }
    }

    private static String cleanName(String name) {
        if (name == null || name.trim().isEmpty()) return "media-server";
        return name.trim();
    }

    private static String cleanPathName(String name, String path) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return pathName(path);
    }

    public static String pathName(String path) {
        if (path == null || path.isEmpty()) return "Домой";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
