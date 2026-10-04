package com.tabletplayer;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

/** Reads a bounded set of compressed sample timestamps without decoding or converting the file. */
final class VideoFrameRateProbe {
    static double read(Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if ("file".equals(uri.getScheme())) extractor.setDataSource(uri.getPath());
            else extractor.setDataSource(uri.toString(), java.util.Collections.emptyMap());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                extractor.selectTrack(i);
                long[] pts = new long[180];
                int count = 0;
                while (count < pts.length && !Thread.currentThread().isInterrupted()) {
                    long value = extractor.getSampleTime();
                    if (value < 0) break;
                    pts[count++] = value;
                    if (!extractor.advance()) break;
                }
                return Thread.currentThread().isInterrupted() ? 0 : FrameTiming.estimateFps(pts, count);
            }
            return 0;
        } finally {
            extractor.release();
        }
    }
}
