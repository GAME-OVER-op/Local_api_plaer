package com.tabletplayer;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;

/** Lightweight container metadata probe used only to protect HDR playback paths. */
final class HdrVideoProbe {
    static final int TYPE_UNKNOWN = 0;
    static final int TYPE_SDR = 1;
    static final int TYPE_HDR10 = 2;
    static final int TYPE_HLG = 3;
    static final int TYPE_DOLBY_VISION = 4;
    static final int TYPE_HDR_OTHER = 5;

    static final class Result {
        final int type;
        final String label;

        Result(int type, String label) {
            this.type = type;
            this.label = label;
        }

        boolean isHdr() {
            return type >= TYPE_HDR10;
        }
    }

    private HdrVideoProbe() {}

    static Result read(Uri uri) {
        if (uri == null || Build.VERSION.SDK_INT < 24) return new Result(TYPE_UNKNOWN, "неизвестно");
        MediaExtractor extractor = new MediaExtractor();
        try {
            String scheme = uri.getScheme();
            if (scheme == null || "file".equalsIgnoreCase(scheme)) {
                String path = uri.getPath();
                if (path == null || path.length() == 0) return new Result(TYPE_UNKNOWN, "неизвестно");
                extractor.setDataSource(path);
            } else {
                extractor.setDataSource(uri.toString());
            }

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;

                String lowerMime = mime.toLowerCase(java.util.Locale.US);
                if (lowerMime.contains("dolby-vision") || lowerMime.contains("dolbyvision")) {
                    return new Result(TYPE_DOLBY_VISION, "Dolby Vision");
                }

                int transfer = getInt(format, MediaFormat.KEY_COLOR_TRANSFER, 0);
                if (transfer == MediaFormat.COLOR_TRANSFER_ST2084) {
                    return new Result(TYPE_HDR10, "HDR10 / PQ");
                }
                if (transfer == MediaFormat.COLOR_TRANSFER_HLG) {
                    return new Result(TYPE_HLG, "HLG");
                }
                if (format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                    return new Result(TYPE_HDR_OTHER, "HDR");
                }
                if (transfer == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) {
                    return new Result(TYPE_SDR, "SDR");
                }
            }
            return new Result(TYPE_UNKNOWN, "неизвестно");
        } catch (Throwable ignored) {
            return new Result(TYPE_UNKNOWN, "неизвестно");
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
        }
    }

    private static int getInt(MediaFormat format, String key, int fallback) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
