package com.tabletplayer;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeadsetBatteryLevelsTest {
    @Test public void appleLevelsAreTenPercentSteps() {
        assertEquals(10, HeadsetBatteryLevels.vendor("+IPHONEACCEV",new Object[]{1,1,0}));
        assertEquals(100, HeadsetBatteryLevels.vendor("+IPHONEACCEV",new Object[]{1,1,9}));
        assertEquals(70, HeadsetBatteryLevels.vendor("+IPHONEACCEV",new Object[]{"2","2","0","1","6"}));
    }
    @Test public void plantronicsLevelsIncludeEmptyAndFull() {
        assertEquals(0, HeadsetBatteryLevels.vendor("+XEVENT",new Object[]{"BATTERY",0,5}));
        assertEquals(50, HeadsetBatteryLevels.vendor("+XEVENT",new Object[]{"BATTERY",2,5}));
        assertEquals(100, HeadsetBatteryLevels.vendor("+XEVENT",new Object[]{"BATTERY",4,5}));
    }
    @Test public void malformedAndUnknownLevelsAreNotShownAsZero() {
        assertEquals(-1, HeadsetBatteryLevels.vendor("+IPHONEACCEV",new Object[]{3,1,8}));
        assertEquals(-1, HeadsetBatteryLevels.vendor("+IPHONEACCEV",new Object[]{1,1,10}));
        assertEquals(-1, HeadsetBatteryLevels.vendor("+XEVENT",new Object[]{"BATTERY",0,1}));
        assertEquals(-1, HeadsetBatteryLevels.vendor("+XEVENT",new Object[]{"BATTERY",6,5}));
        assertEquals(-1, HeadsetBatteryLevels.vendor("unknown",null));
        assertEquals(-1, HeadsetBatteryLevels.percent(255));
        assertEquals(0, HeadsetBatteryLevels.percent(0));
    }
}
