package com.tabletplayer;

import java.util.Arrays;

/** Media timestamps, rather than decoder throughput, decide whether a source needs smoothing. */
final class FrameTiming {
    static final long OUTPUT_INTERVAL_NS = 1_000_000_000L / 60;

    static boolean validFps(double fps) {
        return !Double.isNaN(fps) && !Double.isInfinite(fps) && fps > 0 && fps <= 240;
    }

    static boolean shouldSmooth(double fps) {
        return validFps(fps) && fps < 50.0;
    }

    static float alpha(long outputNs, long previousNs, long currentNs) {
        if (currentNs <= previousNs) return 1f;
        return (float) Math.max(0.0, Math.min(1.0,
                (double) (outputNs - previousNs) / (currentNs - previousNs)));
    }

    // Extractor can deliver B-frames in decode order. Sort presentation timestamps first.
    static double estimateFps(long[] timestampsUs, int count) {
        if (count < 12) return 0;
        long[] sorted = Arrays.copyOf(timestampsUs, count);
        Arrays.sort(sorted);
        long[] gaps = new long[count - 1];
        int used = 0;
        for (int i = 1; i < count; i++) {
            long gap = sorted[i] - sorted[i - 1];
            if (gap > 0 && gap <= 1_000_000) gaps[used++] = gap;
        }
        if (used < 10) return 0;
        Arrays.sort(gaps, 0, used);
        double median = used % 2 == 0
                ? (gaps[used / 2 - 1] + gaps[used / 2]) / 2.0 : gaps[used / 2];
        double fps = 1_000_000.0 / median;
        return validFps(fps) ? fps : 0;
    }
}
