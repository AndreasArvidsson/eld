package com.github.andreasarvidsson.eld.runtime;

import java.util.ArrayDeque;
import java.util.Queue;

/** Deterministic, single-threaded FIFO scheduler for Eld promise continuations. */
public final class EldScheduler {
    private static final Queue<Runnable> QUEUE = new ArrayDeque<>();

    public static void enqueue(final Runnable task) {
        QUEUE.add(task);
    }

    public static void drain() {
        while (!QUEUE.isEmpty()) {
            QUEUE.remove().run();
        }
    }

    private EldScheduler() {}
}
