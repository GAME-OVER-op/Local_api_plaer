package com.tabletplayer;

import java.net.URLEncoder;

public class Util {

    public static int naturalCompare(String a, String b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        int i = 0, j = 0, la = a.length(), lb = b.length();
        while (i < la && j < lb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int i0 = i, j0 = j;
                while (i < la && Character.isDigit(a.charAt(i))) i++;
                while (j < lb && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(i0, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(j0, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return (la - i) - (lb - j);
    }

    public static boolean isVideo(String name) {
        String n = name.toLowerCase();
        String[] ext = {".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts", ".flv", ".3gp", ".mpg", ".mpeg", ".wmv", ".m2ts"};
        for (String e : ext) if (n.endsWith(e)) return true;
        return false;
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) return "0 Б";
        String[] u = {"Б", "КБ", "МБ", "ГБ", "ТБ"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        if (i == 0) return bytes + " " + u[0];
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    public static boolean isApk(String name) {
        return name != null && name.toLowerCase().endsWith(".apk");
    }

    public static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }
}
