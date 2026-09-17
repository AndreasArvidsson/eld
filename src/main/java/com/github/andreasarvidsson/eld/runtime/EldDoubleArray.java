package com.github.andreasarvidsson.eld.runtime;

import java.util.Arrays;

public final class EldDoubleArray extends EldArray<EldDoubleArray> {
    private double[] elements;

    public EldDoubleArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new double[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldDoubleArray(final double[] elements) {
        this.elements = elements;
        size = elements.length;
    }

    public double get(final int index) {
        return elements[normalizeIndex(index)];
    }

    public void set(final int index, final double value) {
        elements[normalizeIndex(index)] = value;
    }

    public void add(final double value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    @Override
    public EldDoubleArray copy() {
        return super.copy();
    }

    @Override
    public EldDoubleArray sliceFrom(final int start) {
        return super.sliceFrom(start);
    }

    @Override
    public EldDoubleArray sliceTo(final int end) {
        return super.sliceTo(end);
    }

    @Override
    public EldDoubleArray slice(final int start, final int end) {
        return super.slice(start, end);
    }

    @Override
    protected EldDoubleArray copyRange(final int from, final int to) {
        return new EldDoubleArray(Arrays.copyOfRange(elements, from, to));
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
