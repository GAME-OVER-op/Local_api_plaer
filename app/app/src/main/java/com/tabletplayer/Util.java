package com.tabletplayer;

import java.net.URLEncoder;
import java.util.Locale;

public final class Util {
    private static final Locale LOCALE = Locale.US;
    private static final String[] VIDEO_EXTENSIONS = {
            ".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts",
            ".flv", ".3gp", ".mpg", ".mpeg", ".wmv", ".m2ts"
    };
    private static final String[] SIZE_UNITS = {"Б", "КБ", "МБ", "ГБ", "ТБ"};

    private Util() {}

    public static int naturalCompare(String left, String right) {
        NameParts a = NameParts.parse(left);
        NameParts b = NameParts.parse(right);

        int base = naturalCompareRaw(a.base, b.base);
        if (base != 0) return base;
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
        if (a == null) a = "";
        if (b == null) b = "";
        int i = 0, j = 0, la = a.length(), lb = b.length();
        while (i < la && j < lb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int aStart = i, bStart = j;
                while (i < la && Character.isDigit(a.charAt(i))) i++;
                while (j < lb && Character.isDigit(b.charAt(j))) j++;
                int aSig = aStart;
                int bSig = bStart;
                while (aSig + 1 < i && a.charAt(aSig) == '0') aSig++;
                while (bSig + 1 < j && b.charAt(bSig) == '0') bSig++;
                int aLen = i - aSig;
                int bLen = j - bSig;
                if (aLen != bLen) return aLen - bLen;
                for (int k = 0; k < aLen; k++) {
                    int diff = a.charAt(aSig + k) - b.charAt(bSig + k);
                    if (diff != 0) return diff;
                }
                int rawLength = (i - aStart) - (j - bStart);
                if (rawLength != 0) return rawLength;
            } else {
                char lowerA = Character.toLowerCase(ca);
                char lowerB = Character.toLowerCase(cb);
                if (lowerA != lowerB) return lowerA - lowerB;
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
                        if (!Character.isDigit(variant.charAt(i))) {
                            numeric = false;
                            break;
                        }
                    }
                }
            }
            return new NameParts(base, variant, extension, hasVariant, numeric);
        }
    }

    public static boolean isVideo(String name) {
        if (name == null) return false;
        String normalized = name.toLowerCase(LOCALE);
        for (String extension : VIDEO_EXTENSIONS) {
            if (normalized.endsWith(extension)) return true;
        }
        return false;
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) return "0 Б";
        int unit = 0;
        double value = bytes;
        while (value >= 1024.0 && unit < SIZE_UNITS.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (unit == 0) return bytes + " " + SIZE_UNITS[0];
        long tenths = Math.round(value * 10.0);
        return (tenths / 10L) + "." + (tenths % 10L) + " " + SIZE_UNITS[unit];
    }

    public static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    public static boolean isApk(String name) {
        return name != null && name.toLowerCase(LOCALE).endsWith(".apk");
    }

    public static String fmtCompactDuration(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder out = new StringBuilder(16);
        if (hours > 0) {
            out.append(hours).append(" ч ");
            appendTwoDigits(out, minutes);
            return out.append(" мин").toString();
        }
        if (minutes > 0) {
            out.append(minutes).append(" мин ");
            appendTwoDigits(out, seconds);
            return out.append(" с").toString();
        }
        return out.append(seconds).append(" с").toString();
    }

    public static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000L;
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        StringBuilder out = new StringBuilder(hours > 0 ? 10 : 5);
        if (hours > 0) {
            out.append(hours).append(':');
            appendTwoDigits(out, minutes);
        } else {
            appendTwoDigits(out, minutes);
        }
        out.append(':');
        appendTwoDigits(out, seconds);
        return out.toString();
    }

    private static void appendTwoDigits(StringBuilder out, long value) {
        if (value < 10L) out.append('0');
        out.append(value);
    }
}
