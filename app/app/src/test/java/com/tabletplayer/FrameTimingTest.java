package com.tabletplayer;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameTimingTest {
    @Test public void thresholdIsStrictlyBelowFifty() {
        for (double fps : new double[]{23.976,24,25,29.97,30,48,49.999})
            assertTrue(FrameTiming.shouldSmooth(fps));
        for (double fps : new double[]{0,-1,50,59.94,60,120,Double.NaN,Double.POSITIVE_INFINITY})
            assertFalse(FrameTiming.shouldSmooth(fps));
    }

    @Test public void thirtyFpsHasOneMidpointAtSixtyHz() {
        assertEquals(0, FrameTiming.alpha(0,0,33_333_333), 0);
        assertEquals(0.5, FrameTiming.alpha(16_666_667,0,33_333_333), 0.000001);
        assertEquals(1, FrameTiming.alpha(33_333_333,0,33_333_333), 0);
    }

    @Test public void twentyFourAndTwentyFiveUseFractionalInterpolation() {
        assertEquals(0.4, FrameTiming.alpha(16_666_667,0,41_666_667), 0.000001);
        assertEquals(0.8, FrameTiming.alpha(33_333_333,0,41_666_667), 0.000001);
        assertEquals(5.0/12, FrameTiming.alpha(16_666_667,0,40_000_000), 0.000001);
        assertEquals(5.0/6, FrameTiming.alpha(33_333_333,0,40_000_000), 0.000001);
    }

    @Test public void timestampsAreSortedAndDuplicateSamplesIgnored() {
        long[] pts = new long[36];
        for (int i=0; i<36; i++) pts[i]=(35-i)/2 * 40_000L;
        assertEquals(25, FrameTiming.estimateFps(pts,pts.length), 0.001);
        assertEquals(0, FrameTiming.estimateFps(new long[20],20), 0);
        assertEquals(0, FrameTiming.estimateFps(new long[4],4), 0);
    }

    @Test public void rationalRatesAreNotRoundedToAnInteger() {
        long[] pts = new long[120];
        for (int i=0; i<pts.length; i++) pts[i]=Math.round(i*1_000_000.0*1001/30000);
        assertEquals(29.97, FrameTiming.estimateFps(pts,pts.length), 0.002);
    }

    @Test public void anInterruptedTimelineNeverExtrapolates() {
        assertEquals(0, FrameTiming.alpha(-1,0,100), 0);
        assertEquals(1, FrameTiming.alpha(200,0,100), 0);
        assertEquals(1, FrameTiming.alpha(50,100,0), 0);
    }
}
