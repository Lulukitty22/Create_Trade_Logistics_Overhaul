package com.vrlulu.createtradelogisticsoverhaul.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects the sections Voxy changes and hands them to open map pages.
 *
 * <p>Fed by the mixin on Voxy's markDirty, so updates are exact and immediate rather than polled.
 * Keys are coalesced per subscriber: during ingest Voxy touches the same section many times, and
 * the page only needs to know it should re-fetch it once.
 */
public final class ChangeHub {
    private static final ChangeHub INSTANCE = new ChangeHub();
    /** Beyond this a page is hopelessly behind; it will catch up on its next full load. */
    private static final int MAX_PENDING = 20_000;

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public static ChangeHub get() {
        return INSTANCE;
    }

    /** Called from Voxy's threads: must stay cheap and never throw. */
    public void record(long sectionKey) {
        for (Subscription sub : subscriptions) {
            sub.add(sectionKey);
        }
    }

    public boolean hasListeners() {
        return !subscriptions.isEmpty();
    }

    public Subscription subscribe() {
        Subscription sub = new Subscription();
        subscriptions.add(sub);
        return sub;
    }

    public void unsubscribe(Subscription sub) {
        subscriptions.remove(sub);
    }

    public static final class Subscription {
        private final LongOpenHashSet pending = new LongOpenHashSet();
        private boolean overflowed;

        private synchronized void add(long key) {
            if (pending.size() >= MAX_PENDING) {
                overflowed = true;
                return;
            }
            pending.add(key);
            notifyAll();
        }

        /** Waits up to waitMs for changes, then returns and clears them. */
        public synchronized long[] drain(long waitMs) throws InterruptedException {
            if (pending.isEmpty()) {
                wait(waitMs);
            }
            long[] keys = pending.toLongArray();
            pending.clear();
            overflowed = false;
            return keys;
        }

        public synchronized boolean didOverflow() {
            return overflowed;
        }
    }
}
