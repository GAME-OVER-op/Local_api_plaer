package com.tabletplayer;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Единый ограничитель сетевых потоков приложения.
 *
 * Глобальный ограничитель удалённых Java-соединений. Playback-cache теперь сам
 * регулирует число Range-потоков, а этот класс не даёт всему приложению уйти
 * выше безопасного общего лимита.
 */
public final class TransferCoordinator {
    public enum Priority {
        PLAYBACK_METADATA,
        PLAYBACK_CACHE,
        MANUAL_DOWNLOAD,
        PREFETCH
    }

    private static final TransferCoordinator INSTANCE = new TransferCoordinator();
    private static final int MAX_JAVA_REMOTE_TRANSFERS = 20;

    private final Semaphore remote = new Semaphore(MAX_JAVA_REMOTE_TRANSFERS, true);
    private final AtomicInteger active = new AtomicInteger(0);

    private TransferCoordinator() {
    }

    public static TransferCoordinator get() {
        return INSTANCE;
    }

    public Lease acquire(Priority priority, String label) throws InterruptedException {
        remote.acquire();
        active.incrementAndGet();
        return new Lease(this, priority, label);
    }

    public Lease tryAcquire(Priority priority, String label, long timeoutMs) throws InterruptedException {
        boolean ok = remote.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        if (!ok) return null;
        active.incrementAndGet();
        return new Lease(this, priority, label);
    }

    public int activeRemoteTransfers() {
        return active.get();
    }

    public int maxRemoteTransfers() {
        return MAX_JAVA_REMOTE_TRANSFERS;
    }

    private void release() {
        int now = active.decrementAndGet();
        if (now < 0) active.set(0);
        remote.release();
    }

    public static final class Lease implements AutoCloseable {
        private final TransferCoordinator owner;
        private final Priority priority;
        private final String label;
        private boolean closed;

        private Lease(TransferCoordinator owner, Priority priority, String label) {
            this.owner = owner;
            this.priority = priority;
            this.label = label;
        }

        public Priority priority() {
            return priority;
        }

        public String label() {
            return label;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.release();
        }
    }
}
