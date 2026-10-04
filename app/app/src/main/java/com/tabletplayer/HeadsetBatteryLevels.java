package com.tabletplayer;

final class HeadsetBatteryLevels {
    static int percent(int value) { return value >= 0 && value <= 100 ? value : -1; }

    static int number(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return -1; }
    }

    static int vendor(String command, Object[] args) {
        if (args == null) return -1;
        if ("+IPHONEACCEV".equals(command) && args.length >= 3) {
            int pairs = number(args[0]);
            if (pairs < 1 || pairs > (args.length - 1) / 2) return -1;
            for (int i = 0; i < pairs; i++) {
                if (number(args[1 + i * 2]) != 1) continue;
                int level = number(args[2 + i * 2]);
                return level >= 0 && level <= 9 ? (level + 1) * 10 : -1;
            }
        }
        if ("+XEVENT".equals(command) && args.length >= 3 && "BATTERY".equals(String.valueOf(args[0]))) {
            int level = number(args[1]), count = number(args[2]);
            return count > 1 && level >= 0 && level < count
                    ? (int) ((long) level * 100 / (count - 1)) : -1;
        }
        return -1;
    }
}
