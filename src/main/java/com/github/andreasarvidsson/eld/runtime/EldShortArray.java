package com.github.andreasarvidsson.eld.runtime;

import java.util.Arrays;

public final class EldShortArray extends EldArray<EldShortArray> {
    private short[] elements;

    public EldShortArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new short[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldShortArray(final short[] elements) {
        this.elements = elements;
        size = elements.length;
    }

    public short get(final int index) {
        return elements[normalizeIndex(index)];
    }

    public void set(final int index, final short value) {
        elements[normalizeIndex(index)] = value;
    }

    public void add(final short value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    @Override
    public EldShortArray copy() {
        return super.copy();
    }

    @Override
    public EldShortArray sliceFrom(final int start) {
        return super.sliceFrom(start);
    }

    @Override
    public EldShortArray sliceTo(final int end) {
        return super.sliceTo(end);
    }

    @Override
    public EldShortArray slice(final int start, final int end) {
        return super.slice(start, end);
    }

    @Override
    protected EldShortArray copyRange(final int from, final int to) {
        return new EldShortArray(Arrays.copyOfRange(elements, from, to));
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
