package com.github.andreasarvidsson.eld.runtime;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class EldByteArray implements EldArray<EldByteArray> {
    private static final int DEFAULT_CAPACITY = 10;

    private byte[] elements;
    private int length;

    public EldByteArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new byte[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldByteArray(final byte[] elements) {
        this.elements = elements;
        length = elements.length;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public boolean isEmpty() {
        return length == 0;
    }

    @Override
    public void clear() {
        length = 0;
    }

    public byte get(final int index) {
        return elements[normalizeIndex(index)];
    }

    public void set(final int index, final byte value) {
        elements[normalizeIndex(index)] = value;
    }

    public void add(final byte value) {
        ensureCapacity(length + 1);
        elements[length++] = value;
    }

    public void add(final byte... values) {
        ensureCapacity(length + values.length);
        System.arraycopy(values, 0, elements, length, values.length);
        length += values.length;
    }

    public void addFront(final byte value) {
        ensureCapacity(length + 1);
        System.arraycopy(elements, 0, elements, 1, length);
        elements[0] = value;
        length++;
    }

    public void addFront(final byte... values) {
        ensureCapacity(length + values.length);
        System.arraycopy(elements, 0, elements, values.length, length);
        System.arraycopy(values, 0, elements, 0, values.length);
        length += values.length;
    }

    public void addAll(final EldByteArray additional) {
        ensureCapacity(length + additional.length);
        System.arraycopy(additional.elements, 0, elements, length, additional.length);
        length += additional.length;
    }

    public void insertAt(final byte value, final int index) {
        final int at = index < 0 ? length + index : index;
        if (at < 0 || at > length) {
            throw new IndexOutOfBoundsException(index);
        }
        ensureCapacity(length + 1);
        System.arraycopy(elements, at, elements, at + 1, length - at);
        elements[at] = value;
        length++;
    }

    public boolean remove(final byte value) {
        for (int index = 0; index < length; index++) {
            if (elements[index] == value) {
                removeAt(index);
                return true;
            }
        }
        return false;
    }

    public int removeAll(final byte value) {
        int removed = 0;
        for (int index = 0; index < length;) {
            if (elements[index] == value) {
                removeAt(index);
                removed++;
            }
            else {
                index++;
            }
        }
        return removed;
    }

    public void copyTo(final byte[] destination, final int offset, final int length) {
        System.arraycopy(elements, 0, destination, offset, length);
    }

    @Override
    public EldByteArray copy() {
        return copyRange(0, length);
    }

    public EldByteArray reverse() {
        final byte[] reversed = new byte[length];
        for (int index = 0; index < length; index++) {
            reversed[index] = elements[length - index - 1];
        }
        return new EldByteArray(reversed);
    }

    @Override
    public EldByteArray sliceFrom(final int start) {
        return slice(start, length);
    }

    @Override
    public EldByteArray sliceTo(final int end) {
        return slice(0, end);
    }

    @Override
    public EldByteArray slice(final int start, final int end) {
        final int from = normalizeSliceIndex(start);
        final int to = normalizeSliceIndex(end);
        Objects.checkFromToIndex(from, to, length);
        return copyRange(from, to);
    }

    public byte removeAt(final int index) {
        final int at = normalizeIndex(index);
        final byte value = elements[at];
        for (int next = at + 1; next < length; next++) {
            set(next - 1, get(next));
        }
        length--;
        return value;
    }

    @Override
    public int normalizeIndex(final int index) {
        return Objects.checkIndex(index < 0 ? length + index : index, length);
    }

    public boolean contains(final byte value) {
        return index(value, 0) != null;
    }

    public @Nullable Integer index(final byte value) {
        return index(value, 0);
    }

    public @Nullable Integer index(final byte value, final int from) {
        final int start = from < 0 ? Math.max(0, length + from) : from;
        for (int i = start; i < length; i++) {
            if (this.elements[i] == value) {
                return i;
            }
        }
        return null;
    }

    public @Nullable Integer lastIndex(final byte value) {
        return lastIndex(value, length - 1);
    }

    public @Nullable Integer lastIndex(final byte value, final int from) {
        final int start = from < 0 ? length + from : from;
        for (int i = Math.min(start, length - 1); i >= 0; i--) {
            if (this.elements[i] == value) {
                return i;
            }
        }
        return null;
    }

    private int normalizeSliceIndex(final int index) {
        final int normalized = index < 0 ? length + index : index;
        return Objects.checkFromToIndex(normalized, normalized, length);
    }

    private void ensureCapacity(final int requiredCapacity) {
        final int currentCapacity = capacity();
        if (requiredCapacity > currentCapacity) {
            resize(grownCapacity(currentCapacity, requiredCapacity));
        }
    }

    private static int grownCapacity(final int currentCapacity, final int requiredCapacity) {
        if (requiredCapacity >= Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Array is too large");
        }
        final long grown =
            Math.max(DEFAULT_CAPACITY, (long) currentCapacity * 2);
        return (int) Math
            .min(Integer.MAX_VALUE, Math.max(requiredCapacity, grown));
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elements[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private EldByteArray copyRange(final int from, final int to) {
        return new EldByteArray(Arrays.copyOfRange(elements, from, to));
    }

    private int capacity() {
        return elements.length;
    }

    private void resize(final int newCapacity) {
        elements = Arrays.copyOf(elements, newCapacity);
    }

    public EldByteArray filter(final MethodHandle predicate) throws Throwable {
        final EldByteArray result = copy();
        int count = 0;
        for (int index = 0; index < length; index++) {
            final byte value = elements[index];
            if (test(predicate, value, index)) {
                result.elements[count++] = value;
            }
        }

        result.length = count;
        return result;
    }

    public EldArray<?> map(final MethodHandle transform, final String elementDescriptor) throws Throwable {
        final boolean indexed = transform.type().parameterCount() == 2;
        return switch (elementDescriptor) {
            case "B" -> {
                final byte[] values = new byte[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? (byte) transform.invoke(elements[index], index)
                        : (byte) transform.invoke(elements[index]);
                }
                yield new EldByteArray(values);
            }
            case "S" -> {
                final short[] values = new short[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? (short) transform.invoke(elements[index], index)
                        : (short) transform.invoke(elements[index]);
                }
                yield new EldShortArray(values);
            }
            case "I" -> {
                final int[] values = new int[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? (int) transform.invoke(elements[index], index)
                        : (int) transform.invoke(elements[index]);
                }
                yield new EldIntArray(values);
            }
            case "J" -> {
                final long[] values = new long[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? (long) transform.invoke(elements[index], index)
                        : (long) transform.invoke(elements[index]);
                }
                yield new EldLongArray(values);
            }
            case "F" -> {
                final float[] values = new float[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? (float) transform.invoke(elements[index], index)
                        : (float) transform.invoke(elements[index]);
                }
                yield new EldFloatArray(values);
            }
            case "D" -> {
                final double[] values = new double[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? (double) transform.invoke(elements[index], index)
                        : (double) transform.invoke(elements[index]);
                }
                yield new EldDoubleArray(values);
            }
            case "Z" -> {
                final boolean[] values = new boolean[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? (boolean) transform.invoke(elements[index], index)
                        : (boolean) transform.invoke(elements[index]);
                }
                yield new EldBooleanArray(values);
            }
            case "C" -> {
                final char[] values = new char[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? (char) transform.invoke(elements[index], index)
                        : (char) transform.invoke(elements[index]);
                }
                yield new EldCharArray(values);
            }
            default -> {
                final Object[] values = new Object[length];
                for (int index = 0; index < length; index++) {
                    values[index] = indexed
                        ? transform.invoke(elements[index], index)
                        : transform.invoke(elements[index]);
                }
                yield new EldObjectArray<>(values);
            }
        };
    }

    public void reverseInPlace() {
        for (int left = 0, right = length - 1;
            left < right; left++, right--) {
            final byte value = elements[left];
            elements[left] = elements[right];
            elements[right] = value;
        }
    }

    public EldByteArray concat(final EldByteArray additional) {
        final EldByteArray result = copy();
        result.ensureCapacity(length + additional.length);
        System.arraycopy(
            additional.elements, 0, result.elements, length, additional.length
        );
        result.length += additional.length;
        return result;
    }

    public Object reduce(final MethodHandle reducer, final Object initial) throws Throwable {
        final boolean indexed = reducer.type().parameterCount() == 3;
        final Class<?> resultType = reducer.type().returnType();
        if (resultType == byte.class) {
            byte result = ((Number) initial).byteValue();
            for (int index = 0; index < length; index++) {
                result = indexed
                    ? (byte) reducer.invoke(result, elements[index], index)
                    : (byte) reducer.invoke(result, elements[index]);
            }
            return result;
        }
        if (resultType == short.class) {
            short result = ((Number) initial).shortValue();
            for (int index = 0; index < length; index++) {
                result = indexed
                    ? (short) reducer.invoke(result, elements[index], index)
                    : (short) reducer.invoke(result, elements[index]);
            }
            return result;
        }
        if (resultType == int.class) {
            int result = ((Number) initial).intValue();
            for (int index = 0; index < length; index++) {
                result = indexed
                    ? (int) reducer.invoke(result, elements[index], index)
                    : (int) reducer.invoke(result, elements[index]);
            }
            return result;
        }
        if (resultType == long.class) {
            long result = ((Number) initial).longValue();
            for (int index = 0; index < length; index++) {
                result = indexed
                    ? (long) reducer.invoke(result, elements[index], index)
                    : (long) reducer.invoke(result, elements[index]);
            }
            return result;
        }
        if (resultType == float.class) {
            float result = ((Number) initial).floatValue();
            for (int index = 0; index < length; index++) {
                result = indexed
                    ? (float) reducer.invoke(result, elements[index], index)
                    : (float) reducer.invoke(result, elements[index]);
            }
            return result;
        }
        if (resultType == double.class) {
            double result = ((Number) initial).doubleValue();
            for (int index = 0; index < length; index++) {
                result = indexed
                    ? (double) reducer.invoke(result, elements[index], index)
                    : (double) reducer.invoke(result, elements[index]);
            }
            return result;
        }
        if (resultType == boolean.class) {
            boolean result = (boolean) initial;
            for (int index = 0; index < length; index++) {
                result = indexed
                    ? (boolean) reducer.invoke(result, elements[index], index)
                    : (boolean) reducer.invoke(result, elements[index]);
            }
            return result;
        }
        if (resultType == char.class) {
            char result = (char) initial;
            for (int index = 0; index < length; index++) {
                result = indexed
                    ? (char) reducer.invoke(result, elements[index], index)
                    : (char) reducer.invoke(result, elements[index]);
            }
            return result;
        }
        Object result = initial;
        for (int index = 0; index < length; index++) {
            result = indexed
                ? reducer.invoke(result, elements[index], index)
                : reducer.invoke(result, elements[index]);
        }
        return result;
    }

    public @Nullable Object find(final MethodHandle predicate) throws Throwable {
        return find(predicate, false);
    }

    public @Nullable Object findLast(final MethodHandle predicate) throws Throwable {
        return find(predicate, true);
    }

    private @Nullable Object find(final MethodHandle predicate, final boolean last) throws Throwable {
        final int start = last ? length - 1 : 0;
        final int end = last ? -1 : length;
        final int step = last ? -1 : 1;
        for (int index = start; index != end; index += step) {
            final byte value = elements[index];
            if (test(predicate, value, index)) {
                return value;
            }
        }
        return null;
    }

    public @Nullable Integer findIndex(final MethodHandle predicate) throws Throwable {
        return findIndex(predicate, false);
    }

    public @Nullable Integer findLastIndex(final MethodHandle predicate) throws Throwable {
        return findIndex(predicate, true);
    }

    private @Nullable Integer findIndex(final MethodHandle predicate, final boolean last) throws Throwable {
        final int start = last ? length - 1 : 0;
        final int end = last ? -1 : length;
        final int step = last ? -1 : 1;
        for (int index = start; index != end; index += step) {
            if (test(predicate, elements[index], index)) {
                return index;
            }
        }
        return null;
    }

    public boolean any(final MethodHandle predicate) throws Throwable {
        for (int index = 0; index < length; index++) {
            if (test(predicate, elements[index], index)) {
                return true;
            }
        }
        return false;
    }

    public boolean all(final MethodHandle predicate) throws Throwable {
        for (int index = 0; index < length; index++) {
            if (!test(predicate, elements[index], index)) {
                return false;
            }
        }
        return true;
    }

    public String join() {
        return join(", ");
    }

    public String join(final String separator) {
        final StringBuilder result = new StringBuilder();
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                result.append(separator);
            }
            result.append(elements[index]);
        }
        return result.toString();
    }

    public boolean remove(final MethodHandle predicate) throws Throwable {
        for (int index = 0; index < length; index++) {
            if (test(predicate, elements[index], index)) {
                removeAt(index);
                return true;
            }
        }
        return false;
    }

    public boolean removeIf(final MethodHandle predicate) throws Throwable {
        boolean removed = false;
        for (int index = 0,
            originalIndex = 0; index < length; originalIndex++) {
            if (test(predicate, elements[index], originalIndex)) {
                removeAt(index);
                removed = true;
            }
            else {
                index++;
            }
        }
        return removed;
    }

    public EldByteArray sort() {
        final EldByteArray result = copy();
        result.sortInPlace();
        return result;
    }

    public void sortInPlace() {
        Arrays.sort(elements, 0, length);
    }

    public void sortInPlace(final MethodHandle comparator) throws Throwable {
        sortWithComparator(Arrays.copyOf(elements, length), 0, length, comparator);
    }

    public EldByteArray sort(final MethodHandle comparator) throws Throwable {
        final EldByteArray result = copy();
        result.sortInPlace(comparator);
        return result;
    }

    private void sortWithComparator(final byte[] scratch, final int from, final int to, final MethodHandle comparator) throws Throwable {
        if (to - from < 2) {
            return;
        }
        final int middle = from + ((to - from) >>> 1);
        sortWithComparator(scratch, from, middle, comparator);
        sortWithComparator(scratch, middle, to, comparator);
        int left = from;
        int right = middle;
        for (int index = from; index < to; index++) {
            if (right == to || (left < middle
                && (int) comparator.invoke(elements[left], elements[right]) <= 0)) {
                scratch[index] = elements[left++];
            }
            else {
                scratch[index] = elements[right++];
            }
        }
        System.arraycopy(scratch, from, elements, from, to - from);
    }

    private static boolean test(final MethodHandle callback, final byte value, final int index) throws Throwable {
        return callback.type().parameterCount() == 2
            ? (boolean) callback.invoke(value, index)
            : (boolean) callback.invoke(value);
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof EldByteArray other && Arrays
            .equals(elements, 0, length, other.elements, 0, other.length);
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < length; i++) {
            result = 31 * result + elements[i];
        }
        return result;
    }

}
