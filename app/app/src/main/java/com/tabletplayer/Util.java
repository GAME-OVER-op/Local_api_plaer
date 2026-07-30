package com.tabletplayer;

import java.net.URLEncoder;

public class Util {

    public static int naturalCompare(String left, String right) {
        NameParts a = NameParts.parse(left);
        NameParts b = NameParts.parse(right);

        int base = naturalCompareRaw(a.base, b.base);
        if (base != 0) return base;

        // Оригинал всегда выше копии: "Серия.mp4" перед "Серия (1).mp4".
        if (a.hasVariant != b.hasVariant) return a.hasVariant ? 1 : -1;
        if (a.hasVariant) {
            if (a.numericVariant != b.numericVariant) return a.numericVariant ? -1 : 1;
            int variant = naturalCompareRaw(a.variant, b.variant);
            if (variant != 0) return variant;
        }
        int ext = naturalCompareRaw(a.extension, b.extension);
        if (ext != 0) return ext;
        return naturalCompareRaw(left, right);
    }

    private static int naturalCompareRaw(String a, String b) {
        a = a == null ? "" : a.toLowerCase(java.util.Locale.US);
        b = b == null ? "" : b.toLowerCase(java.util.Locale.US);
        int i = 0, j = 0, la = a.length(), lb = b.length();
        while (i < la && j < lb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int i0 = i, j0 = j;
                while (i < la && Character.isDigit(a.charAt(i))) i++;
                while (j < lb && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(i0, i).replaceFirst("^0+(?!$)", "");
                String nb = b.substring(j0, j).replaceFirst("^0+(?!$)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
                int rawLength = (i - i0) - (j - j0);
                if (rawLength != 0) return rawLength;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return (la - i) - (lb - j);
    }

    private static final class NameParts {
        final String base;
        final String variant;
        final String extension;
        final boolean hasVariant;
        final boolean numericVariant;

        NameParts(String base, String variant, String extension, boolean hasVariant, boolean numericVariant) {
            this.base = base;
            this.variant = variant;
            this.extension = extension;
            this.hasVariant = hasVariant;
            this.numericVariant = numericVariant;
        }

        static NameParts parse(String value) {
            String name = value == null ? "" : value.trim();
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (slash >= 0) name = name.substring(slash + 1);
            String extension = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                extension = name.substring(dot + 1);
                name = name.substring(0, dot);
            }
            String base = name;
            String variant = "";
            boolean hasVariant = false;
            boolean numeric = false;
            if (name.endsWith(")")) {
                int open = name.lastIndexOf(" (");
                if (open > 0 && open + 2 < name.length() - 1) {
                    variant = name.substring(open + 2, name.length() - 1).trim();
                    base = name.substring(0, open).trim();
                    hasVariant = !variant.isEmpty();
                    numeric = hasVariant;
                    for (int i = 0; i < variant.length(); i++) {
                        if (!Character.isDigit(variant.charAt(i))) { numeric = false; break; }
                    }
                }
            }
            return new NameParts(base, variant, extension, hasVariant, numeric);
        }
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

    public static String fmtCompactDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format(java.util.Locale.US, "%d ч %02d мин", hours, minutes);
        if (minutes > 0) return String.format(java.util.Locale.US, "%d мин %02d с", minutes, seconds);
        return seconds + " с";
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
