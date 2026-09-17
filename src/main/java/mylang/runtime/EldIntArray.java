package mylang.runtime;

import java.util.Arrays;
import java.util.Objects;

public final class EldIntArray {
    private static final int DEFAULT_CAPACITY = 10;
    private int[] elements;
    // The number of elements actually stored in the array.
    // This may be less than the length of the underlying array.
    private int size;

    public EldIntArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new int[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldIntArray(final int[] elements) {
        this.elements = elements;
        size = elements.length;
    }

    public int size() {
        return size;
    }

    public int get(final int index) {
        return elements[Objects.checkIndex(normalizeIndex(index), size)];
    }

    public void set(final int index, final int value) {
        elements[Objects.checkIndex(normalizeIndex(index), size)] = value;
    }

    public void add(final int value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    public EldIntArray copy() {
        return new EldIntArray(Arrays.copyOf(elements, size));
    }

    public EldIntArray sliceFrom(final int start) {
        return slice(start, size);
    }

    public EldIntArray sliceTo(final int end) {
        return slice(0, end);
    }

    public EldIntArray slice(final int start, final int end) {
        final int from = normalizeIndex(start);
        final int to = normalizeIndex(end);
        Objects.checkFromToIndex(from, to, size);
        return new EldIntArray(Arrays.copyOfRange(elements, from, to));
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elements[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private void ensureCapacity(final int capacity) {
        if (capacity <= elements.length) {
            return;
        }
        if (capacity >= Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Array is too large");
        }
        final long grown =
            Math.max(DEFAULT_CAPACITY, (long) elements.length * 2);
        final int newCapacity =
            (int) Math.min(Integer.MAX_VALUE, Math.max(capacity, grown));
        elements = Arrays.copyOf(elements, newCapacity);
    }

    private int normalizeIndex(final int index) {
        return index < 0 ? size + index : index;
    }

}
