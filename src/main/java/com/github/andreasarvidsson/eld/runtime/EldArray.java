package com.github.andreasarvidsson.eld.runtime;

import java.util.Objects;

public abstract class EldArray<T extends EldArray<T>> {
    private static final int DEFAULT_CAPACITY = 10;

    // The number of elements actually stored in the array.
    // This may be less than the length of the underlying array.
    protected int size;

    public final int size() {
        return size;
    }

    public T copy() {
        return copyRange(0, size);
    }

    public T sliceFrom(final int start) {
        return slice(start, size);
    }

    public T sliceTo(final int end) {
        return slice(0, end);
    }

    public T slice(final int start, final int end) {
        final int from = normalizeSliceIndex(start);
        final int to = normalizeSliceIndex(end);
        Objects.checkFromToIndex(from, to, size);
        return copyRange(from, to);
    }

    protected final int normalizeIndex(final int index) {
        return Objects.checkIndex(index < 0 ? size + index : index, size);
    }

    protected final int normalizeSliceIndex(final int index) {
        final int normalized = index < 0 ? size + index : index;
        return Objects.checkFromToIndex(normalized, normalized, size);
    }

    protected final void ensureCapacity(final int requiredCapacity) {
        final int currentCapacity = capacity();
        if (requiredCapacity > currentCapacity) {
            resize(grownCapacity(currentCapacity, requiredCapacity));
        }
    }

    private static int grownCapacity(
        final int currentCapacity,
        final int requiredCapacity
    ) {
        if (requiredCapacity >= Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Array is too large");
        }
        final long grown =
            Math.max(DEFAULT_CAPACITY, (long) currentCapacity * 2);
        return (int) Math
            .min(Integer.MAX_VALUE, Math.max(requiredCapacity, grown));
    }

    @Override
    public final String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            appendElement(sb, i);
        }
        sb.append(']');
        return sb.toString();
    }

    protected abstract void appendElement(StringBuilder builder, int index);

    protected abstract int capacity();

    protected abstract void resize(int newCapacity);

    protected abstract T copyRange(int from, int to);
}
