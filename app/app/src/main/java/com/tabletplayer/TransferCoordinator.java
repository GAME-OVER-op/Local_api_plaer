package com.tabletplayer;

/**
 * Общий планировщик файловых соединений приложения. Оставляет серверу запас
 * для прямого libVLC и не позволяет предзагрузке вытеснить текущее видео.
 */
final class TransferCoordinator {
    static final int PLAYBACK = 0;
    static final int METADATA = 1;
    static final int MANUAL_DOWNLOAD = 2;
    static final int PREFETCH = 3;

    private static final int MAX_ACTIVE = 3;
    private static final Object LOCK = new Object();
    private static final int[] WAITING = new int[4];
    private static int active;

    private TransferCoordinator() {}

    static Lease acquire(int priority) throws InterruptedException {
        int p = Math.max(0, Math.min(WAITING.length - 1, priority));
        synchronized (LOCK) {
            WAITING[p]++;
            try {
                while (active >= MAX_ACTIVE || hasHigherPriorityWaiter(p)) {
                    LOCK.wait();
                }
                active++;
                LOCK.notifyAll();
                return new Lease();
            } finally {
                WAITING[p]--;
                LOCK.notifyAll();
            }
        }
    }

    private static boolean hasHigherPriorityWaiter(int priority) {
        for (int i = 0; i < priority; i++) {
            if (WAITING[i] > 0) return true;
        }
        return false;
    }

    static final class Lease {
        private boolean released;

        void release() {
            synchronized (LOCK) {
                if (released) return;
                released = true;
                if (active > 0) active--;
                LOCK.notifyAll();
            }
        }
    }
}
