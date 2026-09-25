package com.github.andreasarvidsson.eld.runtime;

import org.jspecify.annotations.Nullable;

/** The producer side of an Eld promise. */
public final class PromiseSource<T extends @Nullable Object> {
    private final EldPromise<T> promise = new EldPromise<>();

    @EldApi
    public EldPromise<T> promise() {
        return promise;
    }

    @EldApi
    public void resolve(final @Nullable T value) {
        promise.fulfill(value);
    }

    @EldApi
    public void resolve() {
        promise.fulfillVoid();
    }

    @EldApi
    public void reject(final Throwable error) {
        promise.fail(error);
    }
}
