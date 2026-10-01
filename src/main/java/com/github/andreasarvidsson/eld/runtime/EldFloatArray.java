package com.github.andreasarvidsson.eld.runtime;

import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class EldFloatArray implements EldArray<EldFloatArray> {
    private static final int DEFAULT_CAPACITY = 10;

    private float[] elements;
    private int length;

    public EldFloatArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new float[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldFloatArray(final float[] elements) {
        this.elements = elements;
        length = elements.length;
    }

    @Override
    @EldApi(property = true)
    public int length() {
        return length;
    }

    @Override
    @EldApi
    public boolean isEmpty() {
        return length == 0;
    }

    @Override
    @EldApi
    public void clear() {
        length = 0;
    }

    public float get(final int index) {
        return elements[normalizeIndex(index)];
    }

    @Override
    public Object boxedGet(final int index) {
        return get(index);
    }

    public void set(final int index, final float value) {
        elements[normalizeIndex(index)] = value;
    }

    @EldApi
    public void add(final float value) {
        ensureCapacity(length + 1);
        elements[length++] = value;
    }

    @EldApi
    public void add(final float... values) {
        ensureCapacity(length + values.length);
        System.arraycopy(values, 0, elements, length, values.length);
        length += values.length;
    }

    @EldApi
    public void addFront(final float value) {
        ensureCapacity(length + 1);
        System.arraycopy(elements, 0, elements, 1, length);
        elements[0] = value;
        length++;
    }

    @EldApi
    public void addFront(final float... values) {
        ensureCapacity(length + values.length);
        System.arraycopy(elements, 0, elements, values.length, length);
        System.arraycopy(values, 0, elements, 0, values.length);
        length += values.length;
    }

    @EldApi
    public void addAll(final EldFloatArray additional) {
        ensureCapacity(length + additional.length);
        System.arraycopy(additional.elements, 0, elements, length, additional.length);
        length += additional.length;
    }

    @EldApi
    public void insertAt(final float value, final int index) {
        final int at = index < 0 ? length + index : index;
        if (at < 0 || at > length) {
            throw new IndexOutOfBoundsException(index);
        }
        ensureCapacity(length + 1);
        System.arraycopy(elements, at, elements, at + 1, length - at);
        elements[at] = value;
        length++;
    }

    @EldApi
    public boolean remove(final float value) {
        for (int index = 0; index < length; index++) {
            if (elements[index] == value) {
                removeAt(index);
                return true;
            }
        }
        return false;
    }

    @EldApi
    public int removeAll(final float value) {
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

    public void copyTo(final float[] destination, final int offset, final int length) {
        System.arraycopy(elements, 0, destination, offset, length);
    }

    @Override
    @EldApi
    public EldFloatArray copy() {
        return copyRange(0, length);
    }

    @EldApi
    public EldFloatArray reverse() {
        final float[] reversed = new float[length];
        for (int index = 0; index < length; index++) {
            reversed[index] = elements[length - index - 1];
        }
        return new EldFloatArray(reversed);
    }

    @Override
    public EldFloatArray sliceFrom(final int start) {
        return slice(start, length);
    }

    @Override
    public EldFloatArray sliceTo(final int end) {
        return slice(0, end);
    }

    @Override
    public EldFloatArray slice(final int start, final int end) {
        final int from = normalizeSliceIndex(start);
        final int to = normalizeSliceIndex(end);
        Objects.checkFromToIndex(from, to, length);
        return copyRange(from, to);
    }

    @EldApi
    public float removeAt(final int index) {
        final int at = normalizeIndex(index);
        final float value = elements[at];
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

    @EldApi
    public boolean contains(final float value) {
        return index(value, 0) != null;
    }

    @EldApi
    public @Nullable Integer index(final float value) {
        return index(value, 0);
    }

    @EldApi
    public @Nullable Integer index(final float value, final int from) {
        final int start = from < 0 ? Math.max(0, length + from) : from;
        for (int i = start; i < length; i++) {
            if (this.elements[i] == value) {
                return i;
            }
        }
        return null;
    }

    @EldApi
    public @Nullable Integer lastIndex(final float value) {
        return lastIndex(value, length - 1);
    }

    @EldApi
    public @Nullable Integer lastIndex(final float value, final int from) {
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
    @EldApi
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

    private EldFloatArray copyRange(final int from, final int to) {
        return new EldFloatArray(Arrays.copyOfRange(elements, from, to));
    }

    private int capacity() {
        return elements.length;
    }

    private void resize(final int newCapacity) {
        elements = Arrays.copyOf(elements, newCapacity);
    }

    @EldApi
    public EldFloatArray filter(final MethodHandle predicate) throws Throwable {
        final EldFloatArray result = copy();
        int count = 0;
        for (int index = 0; index < length; index++) {
            final float value = elements[index];
            if (test(predicate, value, index)) {
                result.elements[count++] = value;
            }
        }

        result.length = count;
        return result;
    }

    @EldApi
    public EldTuple partition(final MethodHandle predicate) throws Throwable {
        final EldFloatArray matching = new EldFloatArray();
        final EldFloatArray remaining = new EldFloatArray();
        for (int index = 0; index < length; index++) {
            final float value = get(index);
            if (test(predicate, value, index)) {
                matching.add(value);
            }
            else {
                remaining.add(value);
            }
        }
        return new EldTuple(new Object[] {matching, remaining});
    }

    @EldApi
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

    @EldApi
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

    @EldApi
    public void reverseInPlace() {
        for (int left = 0, right = length - 1;
            left < right; left++, right--) {
            final float value = elements[left];
            elements[left] = elements[right];
            elements[right] = value;
        }
    }

    @EldApi
    public EldFloatArray concat(final EldFloatArray additional) {
        final EldFloatArray result = copy();
        result.ensureCapacity(length + additional.length);
        System.arraycopy(
            additional.elements, 0, result.elements, length, additional.length
        );
        result.length += additional.length;
        return result;
    }

    @EldApi
    public EldFloatArray union(final EldFloatArray additional) {
        final EldFloatArray result = new EldFloatArray();
        for (int index = 0; index < length; index++) {
            final float value = get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        for (int index = 0; index < additional.length; index++) {
            final float value = additional.get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
    public EldFloatArray distinct() {
        final EldFloatArray result = new EldFloatArray();
        for (int index = 0; index < length; index++) {
            final float value = get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
    public EldFloatArray intersect(final EldFloatArray additional) {
        final EldFloatArray result = new EldFloatArray();
        for (int index = 0; index < length; index++) {
            final float value = get(index);
            if (additional.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
    public EldFloatArray subtract(final EldFloatArray additional) {
        final EldFloatArray result = new EldFloatArray();
        for (int index = 0; index < length; index++) {
            final float value = get(index);
            if (!additional.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
    public EldFloatArray difference(final EldFloatArray additional) {
        final EldFloatArray result = subtract(additional);
        for (int index = 0; index < additional.length; index++) {
            final float value = additional.get(index);
            if (!contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
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

    @EldApi
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

    @EldApi
    public @Nullable Object find(final MethodHandle predicate) throws Throwable {
        return find(predicate, false);
    }

    @EldApi
    public @Nullable Object findLast(final MethodHandle predicate) throws Throwable {
        return find(predicate, true);
    }

    private @Nullable Object find(final MethodHandle predicate, final boolean last) throws Throwable {
        final int start = last ? length - 1 : 0;
        final int end = last ? -1 : length;
        final int step = last ? -1 : 1;
        for (int index = start; index != end; index += step) {
            final float value = elements[index];
            if (test(predicate, value, index)) {
                return value;
            }
        }
        return null;
    }

    @EldApi
    public @Nullable Integer findIndex(final MethodHandle predicate) throws Throwable {
        return findIndex(predicate, false);
    }

    @EldApi
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

    @EldApi
    public boolean any(final MethodHandle predicate) throws Throwable {
        for (int index = 0; index < length; index++) {
            if (test(predicate, elements[index], index)) {
                return true;
            }
        }
        return false;
    }

    @EldApi
    public boolean all(final MethodHandle predicate) throws Throwable {
        for (int index = 0; index < length; index++) {
            if (!test(predicate, elements[index], index)) {
                return false;
            }
        }
        return true;
    }

    @EldApi
    public boolean none(final MethodHandle predicate) throws Throwable {
        return !any(predicate);
    }

    @EldApi
    public int count(final MethodHandle predicate) throws Throwable {
        int matching = 0;
        for (int index = 0; index < length; index++) {
            if (test(predicate, elements[index], index)) {
                matching++;
            }
        }
        return matching;
    }

    @EldApi
    public String join() {
        return join(", ");
    }

    @EldApi
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

    @EldApi
    public boolean remove(final MethodHandle predicate) throws Throwable {
        for (int index = 0; index < length; index++) {
            if (test(predicate, elements[index], index)) {
                removeAt(index);
                return true;
            }
        }
        return false;
    }

    @EldApi
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

    @EldApi
    public EldFloatArray sort() {
        final EldFloatArray result = copy();
        result.sortInPlace();
        return result;
    }

    @EldApi
    public void sortInPlace() {
        Arrays.sort(elements, 0, length);
    }

    @EldApi
    public void sortInPlace(final MethodHandle comparator) throws Throwable {
        sortWithComparator(Arrays.copyOf(elements, length), 0, length, comparator);
    }

    @EldApi
    public EldFloatArray sort(final MethodHandle comparator) throws Throwable {
        final EldFloatArray result = copy();
        result.sortInPlace(comparator);
        return result;
    }

    private void sortWithComparator(final float[] scratch, final int from, final int to, final MethodHandle comparator) throws Throwable {
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

    private static boolean test(final MethodHandle callback, final float value, final int index) throws Throwable {
        return callback.type().parameterCount() == 2
            ? (boolean) callback.invoke(value, index)
            : (boolean) callback.invoke(value);
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof EldFloatArray other && Arrays
            .equals(elements, 0, length, other.elements, 0, other.length);
    }

    @Override
    @EldApi
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < length; i++) {
            result = 31 * result + Float.hashCode(elements[i]);
        }
        return result;
    }

}
