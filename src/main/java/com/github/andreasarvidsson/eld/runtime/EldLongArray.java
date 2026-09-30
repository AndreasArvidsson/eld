package com.github.andreasarvidsson.eld.runtime;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class EldLongArray implements EldArray<EldLongArray> {
    private static final int DEFAULT_CAPACITY = 10;

    private long[] elements;
    private int length;

    public EldLongArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new long[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldLongArray(final long[] elements) {
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

    public long get(final int index) {
        return elements[normalizeIndex(index)];
    }

    @Override
    public Object boxedGet(final int index) {
        return get(index);
    }

    public void set(final int index, final long value) {
        elements[normalizeIndex(index)] = value;
    }

    public void add(final long value) {
        ensureCapacity(length + 1);
        elements[length++] = value;
    }

    public void add(final long... values) {
        ensureCapacity(length + values.length);
        System.arraycopy(values, 0, elements, length, values.length);
        length += values.length;
    }

    public void addFront(final long value) {
        ensureCapacity(length + 1);
        System.arraycopy(elements, 0, elements, 1, length);
        elements[0] = value;
        length++;
    }

    public void addFront(final long... values) {
        ensureCapacity(length + values.length);
        System.arraycopy(elements, 0, elements, values.length, length);
        System.arraycopy(values, 0, elements, 0, values.length);
        length += values.length;
    }

    public void addAll(final EldLongArray additional) {
        ensureCapacity(length + additional.length);
        System.arraycopy(additional.elements, 0, elements, length, additional.length);
        length += additional.length;
    }

    public void insertAt(final long value, final int index) {
        final int at = index < 0 ? length + index : index;
        if (at < 0 || at > length) {
            throw new IndexOutOfBoundsException(index);
        }
        ensureCapacity(length + 1);
        System.arraycopy(elements, at, elements, at + 1, length - at);
        elements[at] = value;
        length++;
    }

    public boolean remove(final long value) {
        for (int index = 0; index < length; index++) {
            if (elements[index] == value) {
                removeAt(index);
                return true;
            }
        }
        return false;
    }

    public int removeAll(final long value) {
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

    public void copyTo(final long[] destination, final int offset, final int length) {
        System.arraycopy(elements, 0, destination, offset, length);
    }

    @Override
    public EldLongArray copy() {
        return copyRange(0, length);
    }

    public EldLongArray reverse() {
        final long[] reversed = new long[length];
        for (int index = 0; index < length; index++) {
            reversed[index] = elements[length - index - 1];
        }
        return new EldLongArray(reversed);
    }

    @Override
    public EldLongArray sliceFrom(final int start) {
        return slice(start, length);
    }

    @Override
    public EldLongArray sliceTo(final int end) {
        return slice(0, end);
    }

    @Override
    public EldLongArray slice(final int start, final int end) {
        final int from = normalizeSliceIndex(start);
        final int to = normalizeSliceIndex(end);
        Objects.checkFromToIndex(from, to, length);
        return copyRange(from, to);
    }

    public long removeAt(final int index) {
        final int at = normalizeIndex(index);
        final long value = elements[at];
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

    public boolean contains(final long value) {
        return index(value, 0) != null;
    }

    public @Nullable Integer index(final long value) {
        return index(value, 0);
    }

    public @Nullable Integer index(final long value, final int from) {
        final int start = from < 0 ? Math.max(0, length + from) : from;
        for (int i = start; i < length; i++) {
            if (this.elements[i] == value) {
                return i;
            }
        }
        return null;
    }

    public @Nullable Integer lastIndex(final long value) {
        return lastIndex(value, length - 1);
    }

    public @Nullable Integer lastIndex(final long value, final int from) {
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

    private EldLongArray copyRange(final int from, final int to) {
        return new EldLongArray(Arrays.copyOfRange(elements, from, to));
    }

    private int capacity() {
        return elements.length;
    }

    private void resize(final int newCapacity) {
        elements = Arrays.copyOf(elements, newCapacity);
    }

    public EldLongArray filter(final MethodHandle predicate) throws Throwable {
        final EldLongArray result = copy();
        int count = 0;
        for (int index = 0; index < length; index++) {
            final long value = elements[index];
            if (test(predicate, value, index)) {
                result.elements[count++] = value;
            }
        }

        result.length = count;
        return result;
    }

    public EldTuple partition(final MethodHandle predicate) throws Throwable {
        final EldLongArray matching = new EldLongArray();
        final EldLongArray remaining = new EldLongArray();
        for (int index = 0; index < length; index++) {
            final long value = get(index);
            if (test(predicate, value, index)) {
                matching.add(value);
            }
            else {
                remaining.add(value);
            }
        }
        return new EldTuple(new Object[] {matching, remaining});
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

    public EldArray<?> flatMap(final MethodHandle transform, final String elementDescriptor) throws Throwable {
        final boolean indexed = transform.type().parameterCount() == 2;
        final EldArray<?>[] parts = new EldArray<?>[length];
        int total = 0;
        for (int index = 0; index < length; index++) {
            final EldArray<?> part = indexed
                ? (EldArray<?>) transform.invoke(elements[index], index)
                : (EldArray<?>) transform.invoke(elements[index]);
            parts[index] = part;
            total = Math.addExact(total, part.length());
        }
        return switch (elementDescriptor) {
            case "B" -> {
                final byte[] values = new byte[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldByteArray) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldByteArray(values);
            }
            case "S" -> {
                final short[] values = new short[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldShortArray) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldShortArray(values);
            }
            case "I" -> {
                final int[] values = new int[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldIntArray) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldIntArray(values);
            }
            case "J" -> {
                final long[] values = new long[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldLongArray) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldLongArray(values);
            }
            case "F" -> {
                final float[] values = new float[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldFloatArray) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldFloatArray(values);
            }
            case "D" -> {
                final double[] values = new double[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldDoubleArray) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldDoubleArray(values);
            }
            case "Z" -> {
                final boolean[] values = new boolean[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldBooleanArray) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldBooleanArray(values);
            }
            case "C" -> {
                final char[] values = new char[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldCharArray) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldCharArray(values);
            }
            default -> {
                final Object[] values = new Object[total];
                int offset = 0;
                for (final EldArray<?> part : parts) {
                    ((EldObjectArray<?>) part).copyTo(values, offset, part.length());
                    offset += part.length();
                }
                yield new EldObjectArray<>(values);
            }
        };
    }

    public void reverseInPlace() {
        for (int left = 0, right = length - 1;
            left < right; left++, right--) {
            final long value = elements[left];
            elements[left] = elements[right];
            elements[right] = value;
        }
    }

    public EldLongArray concat(final EldLongArray additional) {
        final EldLongArray result = copy();
        result.ensureCapacity(length + additional.length);
        System.arraycopy(
            additional.elements, 0, result.elements, length, additional.length
        );
        result.length += additional.length;
        return result;
    }

    public EldLongArray union(final EldLongArray additional) {
        final EldLongArray result = new EldLongArray();
        for (int index = 0; index < length; index++) {
            final long value = get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        for (int index = 0; index < additional.length; index++) {
            final long value = additional.get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    public EldLongArray distinct() {
        final EldLongArray result = new EldLongArray();
        for (int index = 0; index < length; index++) {
            final long value = get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    public EldLongArray intersect(final EldLongArray additional) {
        final EldLongArray result = new EldLongArray();
        for (int index = 0; index < length; index++) {
            final long value = get(index);
            if (additional.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    public EldLongArray subtract(final EldLongArray additional) {
        final EldLongArray result = new EldLongArray();
        for (int index = 0; index < length; index++) {
            final long value = get(index);
            if (!additional.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    public EldLongArray difference(final EldLongArray additional) {
        final EldLongArray result = subtract(additional);
        for (int index = 0; index < additional.length; index++) {
            final long value = additional.get(index);
            if (!contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    public EldObjectArray<EldTuple> zip(final EldArray<?> additional) {
        if (length != additional.length()) {
            throw new IllegalArgumentException("Cannot zip arrays of different lengths");
        }
        final Object[] tuples = new Object[length];
        for (int index = 0; index < length; index++) {
            tuples[index] = new EldTuple(new Object[] {elements[index], additional.boxedGet(index)});
        }
        return new EldObjectArray<>(tuples);
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
            final long value = elements[index];
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

    public boolean none(final MethodHandle predicate) throws Throwable {
        return !any(predicate);
    }

    public int count(final MethodHandle predicate) throws Throwable {
        int matching = 0;
        for (int index = 0; index < length; index++) {
            if (test(predicate, elements[index], index)) {
                matching++;
            }
        }
        return matching;
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

    public EldLongArray sort() {
        final EldLongArray result = copy();
        result.sortInPlace();
        return result;
    }

    public void sortInPlace() {
        Arrays.sort(elements, 0, length);
    }

    public void sortInPlace(final MethodHandle comparator) throws Throwable {
        sortWithComparator(Arrays.copyOf(elements, length), 0, length, comparator);
    }

    public EldLongArray sort(final MethodHandle comparator) throws Throwable {
        final EldLongArray result = copy();
        result.sortInPlace(comparator);
        return result;
    }

    private void sortWithComparator(final long[] scratch, final int from, final int to, final MethodHandle comparator) throws Throwable {
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

    private static boolean test(final MethodHandle callback, final long value, final int index) throws Throwable {
        return callback.type().parameterCount() == 2
            ? (boolean) callback.invoke(value, index)
            : (boolean) callback.invoke(value);
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof EldLongArray other && Arrays
            .equals(elements, 0, length, other.elements, 0, other.length);
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < length; i++) {
            result = 31 * result + Long.hashCode(elements[i]);
        }
        return result;
    }

}
