package com.github.andreasarvidsson.eld.runtime;

import java.util.Arrays;

public final class EldLongArray extends EldArray<EldLongArray> {
    private long[] elements;

    public EldLongArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new long[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldLongArray(final long[] elements) {
        this.elements = elements;
        size = elements.length;
    }

    public long get(final int index) {
        return elements[normalizeIndex(index)];
    }

    public void set(final int index, final long value) {
        elements[normalizeIndex(index)] = value;
    }

    public void add(final long value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    public void copyTo(
        final long[] destination,
        final int offset,
        final int length
    ) {
        System.arraycopy(elements, 0, destination, offset, length);
    }

    @Override
    public EldLongArray copy() {
        return super.copy();
    }

    public void sort() {
        Arrays.sort(elements, 0, size);
    }

    @Override
    public EldLongArray sliceFrom(final int start) {
        return super.sliceFrom(start);
    }

    @Override
    public EldLongArray sliceTo(final int end) {
        return super.sliceTo(end);
    }

    @Override
    public EldLongArray slice(final int start, final int end) {
        return super.slice(start, end);
    }

    @Override
    protected EldLongArray copyRange(final int from, final int to) {
        return new EldLongArray(Arrays.copyOfRange(elements, from, to));
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
