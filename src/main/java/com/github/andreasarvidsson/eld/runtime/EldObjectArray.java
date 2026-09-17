package com.github.andreasarvidsson.eld.runtime;

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

public final class EldObjectArray<T extends @Nullable Object>
    extends EldArray<EldObjectArray<T>> {
    private @Nullable Object[] elements;

    public EldObjectArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new Object[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldObjectArray(final @Nullable Object[] elements) {
        this.elements = elements;
        size = elements.length;
    }

    // Storage erases T and its nullness. The compiler and typed writers supply values of T.
    @SuppressWarnings({"unchecked", "NullAway"})
    public T get(final int index) {
        return (T) elements[normalizeIndex(index)];
    }

    public void set(final int index, final T value) {
        elements[normalizeIndex(index)] = value;
    }

    public void add(final T value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    @Override
    public EldObjectArray<T> copy() {
        return super.copy();
    }

    @Override
    public EldObjectArray<T> sliceFrom(final int start) {
        return super.sliceFrom(start);
    }

    @Override
    public EldObjectArray<T> sliceTo(final int end) {
        return super.sliceTo(end);
    }

    @Override
    public EldObjectArray<T> slice(final int start, final int end) {
        return super.slice(start, end);
    }

    @Override
    protected EldObjectArray<T> copyRange(final int from, final int to) {
        return new EldObjectArray<>(Arrays.copyOfRange(elements, from, to));
    }

    @Override
    protected void appendElement(final StringBuilder builder, final int index) {
        builder.append(elements[index]);
    }

    @Override
    protected int capacity() {
        return elements.length;
    }

    @Override
    protected void resize(final int newCapacity) {
        elements = Arrays.copyOf(elements, newCapacity);
    }

}
