package com.github.andreasarvidsson.eld.runtime;

import java.util.Arrays;

public final class EldByteArray extends EldArray<EldByteArray> {
    private byte[] elements;

    public EldByteArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new byte[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldByteArray(final byte[] elements) {
        this.elements = elements;
        size = elements.length;
    }

    public byte get(final int index) {
        return elements[normalizeIndex(index)];
    }

    public void set(final int index, final byte value) {
        elements[normalizeIndex(index)] = value;
    }

    public void add(final byte value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    @Override
    public EldByteArray copy() {
        return super.copy();
    }

    public void sort() {
        Arrays.sort(elements, 0, size);
    }

    @Override
    public EldByteArray sliceFrom(final int start) {
        return super.sliceFrom(start);
    }

    @Override
    public EldByteArray sliceTo(final int end) {
        return super.sliceTo(end);
    }

    @Override
    public EldByteArray slice(final int start, final int end) {
        return super.slice(start, end);
    }

    @Override
    protected EldByteArray copyRange(final int from, final int to) {
        return new EldByteArray(Arrays.copyOfRange(elements, from, to));
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
