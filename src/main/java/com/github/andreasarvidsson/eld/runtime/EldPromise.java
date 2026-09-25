package com.github.andreasarvidsson.eld.runtime;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** An eventual Eld value. This type does not own or create a thread. */
public final class EldPromise<T extends @Nullable Object> {
    public enum State {
        PENDING,
        FULFILLED,
        REJECTED
    }

    @FunctionalInterface
    public interface Continuation<T extends @Nullable Object> {
        void resume(@Nullable T value, @Nullable Throwable error);
    }

    private State state = State.PENDING;
    private boolean fulfilledWithValue;
    private @Nullable T value;
    private @Nullable Throwable error;
    private final List<Continuation<T>> continuations = new ArrayList<>();

    @EldApi
    public static <T extends @Nullable Object> EldPromise<T> resolve(
        final @Nullable T value
    ) {
        final PromiseSource<T> source = new PromiseSource<>();
        source.resolve(value);
        return source.promise();
    }

    @EldApi
    public static EldPromise<@Nullable Void> resolve() {
        final PromiseSource<@Nullable Void> source = new PromiseSource<>();
        source.resolve();
        return source.promise();
    }

    @EldApi
    public static EldPromise<@Nullable Object> reject(final Throwable error) {
        final PromiseSource<@Nullable Object> source = new PromiseSource<>();
        source.reject(error);
        return source.promise();
    }

    @EldApi
    public static EldPromise<EldObjectArray<@Nullable Object>> all(
        final EldObjectArray<EldPromise<?>> promises
    ) {
        final PromiseSource<EldObjectArray<@Nullable Object>> source =
            new PromiseSource<>();
        final int size = promises.size();
        if (size == 0) {
            source.resolve(new EldObjectArray<>());
            return source.promise();
        }
        final @Nullable Object[] values = new Object[size];
        final int[] remaining = {size};
        for (int i = 0; i < size; i++) {
            final int index = i;
            final EldPromise<?> promise = promises.get(i);
            promise.then((value, failure) -> {
                if (failure != null) {
                    source.reject(failure);
                    return;
                }
                values[index] = value;
                remaining[0]--;
                if (remaining[0] != 0) {
                    return;
                }
                source.resolve(new EldObjectArray<>(values));
            });
        }
        return source.promise();
    }

    public State state() {
        return state;
    }

    public @Nullable T value() {
        return value;
    }

    public @Nullable Throwable error() {
        return error;
    }

    public @Nullable T awaitNow() throws Throwable {
        if (state == State.PENDING) {
            throw new IllegalStateException("Promise is still pending");
        }
        if (state == State.REJECTED) {
            throw Objects.requireNonNull(error);
        }
        return value;
    }

    public void then(final Continuation<T> continuation) {
        if (state == State.PENDING) {
            continuations.add(continuation);
            return;
        }
        enqueue(continuation);
    }

    public void then(final MethodHandle continuation, final Object state) {
        then((value, failure) -> {
            try {
                continuation.invokeExact(state, (Object) value, failure);
            }
            catch (final Throwable error) {
                throw new IllegalStateException(
                    "Async continuation failed",
                    error
                );
            }
        });
    }

    public void fulfill(final @Nullable T result) {
        if (state != State.PENDING) {
            return;
        }
        fulfilledWithValue = true;
        value = result;
        state = State.FULFILLED;
        enqueueAll();
    }

    void fulfillVoid() {
        if (state != State.PENDING) {
            return;
        }
        state = State.FULFILLED;
        enqueueAll();
    }

    public void fail(final Throwable failure) {
        if (state != State.PENDING) {
            return;
        }
        error = failure;
        state = State.REJECTED;
        enqueueAll();
    }

    @Override
    public String toString() {
        return switch (state) {
            case PENDING -> "Promise<pending>";
            case FULFILLED -> fulfilledWithValue
                ? "Promise<fulfilled: %s>".formatted(value)
                : "Promise<fulfilled: void>";
            case REJECTED -> "Promise<rejected: %s>".formatted(error);
        };
    }

    private void enqueueAll() {
        continuations.forEach(this::enqueue);
        continuations.clear();
    }

    private void enqueue(final Continuation<T> continuation) {
        final @Nullable T settledValue = value;
        final @Nullable Throwable settledError = error;
        EldScheduler
            .enqueue(() -> continuation.resume(settledValue, settledError));
    }
}
