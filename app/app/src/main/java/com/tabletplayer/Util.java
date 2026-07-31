package com.tabletplayer;

import java.net.URLEncoder;
import java.util.Locale;

public class Util {

    public static int naturalCompare(String a, String b) {
        SortName aa = SortName.from(a);
        SortName bb = SortName.from(b);
        int c = naturalComparePlain(aa.root, bb.root);
        if (c != 0) return c;
        if (aa.copyRank != bb.copyRank) return aa.copyRank - bb.copyRank;
        if (aa.copyRank == 1 && aa.copyNumber != bb.copyNumber) {
            return aa.copyNumber < bb.copyNumber ? -1 : 1;
        }
        c = naturalComparePlain(aa.copyText, bb.copyText);
        if (c != 0) return c;
        c = naturalComparePlain(aa.ext, bb.ext);
        if (c != 0) return c;
        return naturalComparePlain(a == null ? "" : a, b == null ? "" : b);
    }

    private static int naturalComparePlain(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        a = a.toLowerCase(Locale.US);
        b = b.toLowerCase(Locale.US);
        int i = 0, j = 0, la = a.length(), lb = b.length();
        while (i < la && j < lb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int i0 = i, j0 = j;
                while (i < la && Character.isDigit(a.charAt(i))) i++;
                while (j < lb && Character.isDigit(b.charAt(j))) j++;
                int ia = firstNonZero(a, i0, i);
                int ib = firstNonZero(b, j0, j);
                int lena = i - ia;
                int lenb = j - ib;
                if (lena != lenb) return lena - lenb;
                for (int k = 0; k < lena; k++) {
                    int d = a.charAt(ia + k) - b.charAt(ib + k);
                    if (d != 0) return d;
                }
                int rawLenDiff = (i - i0) - (j - j0);
                if (rawLenDiff != 0) return rawLenDiff;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return (la - i) - (lb - j);
    }

    private static int firstNonZero(String s, int start, int end) {
        int i = start;
        while (i < end - 1 && s.charAt(i) == '0') i++;
        return i;
    }

    private static final class SortName {
        final String root;
        final String ext;
        final int copyRank; // 0 — оригинал, 1 — числовой суффикс, 2 — текстовый суффикс
        final long copyNumber;
        final String copyText;

        SortName(String root, String ext, int copyRank, long copyNumber, String copyText) {
            this.root = root;
            this.ext = ext;
            this.copyRank = copyRank;
            this.copyNumber = copyNumber;
            this.copyText = copyText;
        }

        static SortName from(String name) {
            if (name == null) name = "";
            String lower = name.toLowerCase(Locale.US);
            int slash = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\'));
            int dot = lower.lastIndexOf('.');
            if (dot <= slash) dot = -1;
            String base = dot >= 0 ? name.substring(0, dot) : name;
            String ext = dot >= 0 ? name.substring(dot) : "";
            int rank = 0;
            long number = 0;
            String text = "";
            String root = base;
            if (base.endsWith(")")) {
                int open = base.lastIndexOf(" (");
                if (open > 0) {
                    String inside = base.substring(open + 2, base.length() - 1).trim();
                    if (inside.length() > 0) {
                        root = base.substring(0, open);
                        if (isDigits(inside)) {
                            rank = 1;
                            try { number = Long.parseLong(inside); } catch (Exception ignored) { number = Long.MAX_VALUE; }
                        } else {
                            rank = 2;
                            text = inside;
                        }
                    }
                }
            }
            return new SortName(root, ext, rank, number, text);
        }

        private static boolean isDigits(String s) {
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) return false;
            }
            return s.length() > 0;
        }
    }

    public static boolean isVideo(String name) {
        String n = name.toLowerCase(Locale.US);
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
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    public static boolean isApk(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".apk");
    }

    public static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
