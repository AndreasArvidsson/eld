package com.github.andreasarvidsson.eld.runtime;

import java.lang.reflect.Array;
import java.util.Objects;

public abstract class EldArray<T extends EldArray<T>> {
    private static final int DEFAULT_CAPACITY = 10;

    public static EldArray<?> fromObjectArray(
        final Object source,
        final String elementDescriptor
    ) {
        if (!(source instanceof EldObjectArray<?> objects)) {
            return (EldArray<?>) source;
        }
        final Class<?> component = switch (elementDescriptor) {
            case "B" -> byte.class;
            case "S" -> short.class;
            case "I" -> int.class;
            case "J" -> long.class;
            case "F" -> float.class;
            case "D" -> double.class;
            case "Z" -> boolean.class;
            case "C" -> char.class;
            default -> null;
        };
        if (component == null) {
            return objects;
        }
        final Object values = Array.newInstance(component, objects.size());
        for (int i = 0; i < objects.size(); i++) {
            Array.set(values, i, objects.get(i));
        }
        return switch (elementDescriptor) {
            case "B" -> new EldByteArray((byte[]) values);
            case "S" -> new EldShortArray((short[]) values);
            case "I" -> new EldIntArray((int[]) values);
            case "J" -> new EldLongArray((long[]) values);
            case "F" -> new EldFloatArray((float[]) values);
            case "D" -> new EldDoubleArray((double[]) values);
            case "Z" -> new EldBooleanArray((boolean[]) values);
            case "C" -> new EldCharArray((char[]) values);
            default -> throw new IllegalStateException(
                "Unexpected array element descriptor: " + elementDescriptor
            );
        };
    }

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

    protected abstract void appendElement(
        final StringBuilder builder,
        final int index
    );

    protected abstract int capacity();

    protected abstract void resize(final int newCapacity);

    protected abstract T copyRange(final int from, final int to);
}
