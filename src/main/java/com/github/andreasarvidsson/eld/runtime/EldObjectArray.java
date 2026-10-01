package com.github.andreasarvidsson.eld.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class EldObjectArray<T extends @Nullable Object>
    implements EldArray<EldObjectArray<T>> {
    private static final int DEFAULT_CAPACITY = 10;
    private static final MethodHandle ARRAY_IDENTITY =
        MethodHandles.identity(EldArray.class);

    private @Nullable Object[] elements;
    private int length;

    public EldObjectArray() {
        // Initialize the array with zero elements initially to save memory on arrays that never grow.
        elements = new Object[0];
    }

    // Doesn't need to copy the array; it is only used for:
    // 1. Literal arrays, where the array is already fully constructed and won't be modified externally.
    // 2. Internally in this class.
    public EldObjectArray(final @Nullable Object[] elements) {
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
        // Clear references to allow garbage collection.
        Arrays.fill(elements, 0, length, null);
        length = 0;
    }

    // Storage erases T and its nullness. The compiler and typed writers supply values of T.
    @SuppressWarnings({"unchecked", "NullAway"})
    public T get(final int index) {
        return (T) elements[normalizeIndex(index)];
    }

    @Override
    public @Nullable Object boxedGet(final int index) {
        return get(index);
    }

    public void set(final int index, final T value) {
        elements[normalizeIndex(index)] = value;
    }

    @EldApi
    public void add(final T value) {
        ensureCapacity(length + 1);
        elements[length++] = value;
    }

    @EldApi
    public void add(final Object[] values) {
        ensureCapacity(length + values.length);
        System.arraycopy(values, 0, elements, length, values.length);
        length += values.length;
    }

    @EldApi
    public void addFront(final T value) {
        ensureCapacity(length + 1);
        System.arraycopy(elements, 0, elements, 1, length);
        elements[0] = value;
        length++;
    }

    @EldApi
    public void addFront(final Object[] values) {
        ensureCapacity(length + values.length);
        System.arraycopy(elements, 0, elements, values.length, length);
        System.arraycopy(values, 0, elements, 0, values.length);
        length += values.length;
    }

    @EldApi
    public void addAll(final EldObjectArray<T> additional) {
        ensureCapacity(length + additional.length);
        System.arraycopy(additional.elements, 0, elements, length, additional.length);
        length += additional.length;
    }

    @EldApi
    public void insertAt(final T value, final int index) {
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
    public boolean remove(final Object value) {
        for (int index = 0; index < length; index++) {
            if (EldEquality.dynamicEquals(elements[index], value)) {
                removeAt(index);
                return true;
            }
        }
        return false;
    }

    @EldApi
    public int removeAll(final Object value) {
        int removed = 0;
        for (int index = 0; index < length;) {
            if (EldEquality.dynamicEquals(elements[index], value)) {
                removeAt(index);
                removed++;
            }
            else {
                index++;
            }
        }
        return removed;
    }

    @EldApi
    public T removeAt(final int index) {
        final int at = normalizeIndex(index);
        final T value = get(at);
        for (int next = at + 1; next < length; next++) {
            elements[next - 1] = elements[next];
        }
        elements[--length] = null;
        return value;
    }

    public void copyTo(final @Nullable Object[] destination, final int offset, final int length) {
        System.arraycopy(elements, 0, destination, offset, length);
    }

    @Override
    @EldApi
    public EldObjectArray<T> copy() {
        return copyRange(0, length);
    }

    @EldApi
    public EldObjectArray<T> reverse() {
        final @Nullable Object[] reversed = new Object[length];
        for (int index = 0; index < length; index++) {
            reversed[index] = elements[length - index - 1];
        }
        return new EldObjectArray<>(reversed);
    }

    @Override
    public EldObjectArray<T> sliceFrom(final int start) {
        return slice(start, length);
    }

    @Override
    public EldObjectArray<T> sliceTo(final int end) {
        return slice(0, end);
    }

    @Override
    public EldObjectArray<T> slice(final int start, final int end) {
        final int from = normalizeSliceIndex(start);
        final int to = normalizeSliceIndex(end);
        Objects.checkFromToIndex(from, to, length);
        return copyRange(from, to);
    }

    @Override
    public int normalizeIndex(final int index) {
        return Objects.checkIndex(index < 0 ? length + index : index, length);
    }

    @EldApi
    public boolean contains(final @Nullable Object value) {
        return index(value, 0) != null;
    }

    @EldApi
    public @Nullable Integer index(final @Nullable Object value) {
        return index(value, 0);
    }

    @EldApi
    public @Nullable Integer index(final @Nullable Object value, final int from) {
        final int start = from < 0 ? Math.max(0, length + from) : from;
        for (int i = start; i < length; i++) {
            if (EldEquality.dynamicEquals(elements[i], value)) {
                return i;
            }
        }
        return null;
    }

    @EldApi
    public @Nullable Integer lastIndex(final @Nullable Object value) {
        return lastIndex(value, length - 1);
    }

    @EldApi
    public @Nullable Integer lastIndex(final @Nullable Object value, final int from) {
        final int start = from < 0 ? length + from : from;
        for (int i = Math.min(start, length - 1); i >= 0; i--) {
            if (EldEquality.dynamicEquals(elements[i], value)) {
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

    private EldObjectArray<T> copyRange(final int from, final int to) {
        return new EldObjectArray<>(Arrays.copyOfRange(elements, from, to));
    }

    private int capacity() {
        return elements.length;
    }

    private void resize(final int newCapacity) {
        elements = Arrays.copyOf(elements, newCapacity);
    }

    @EldApi
    public EldObjectArray<T> filter(final MethodHandle predicate) throws Throwable {
        final EldObjectArray<T> result = copy();
        int count = 0;
        for (int index = 0; index < length; index++) {
            final @Nullable Object value = elements[index];
            if (test(predicate, value, index)) {
                result.elements[count++] = value;
            }
        }
        Arrays.fill(result.elements, count, result.length, null);
        result.length = count;
        return result;
    }

    @EldApi
    public EldTuple partition(final MethodHandle predicate) throws Throwable {
        final EldObjectArray<T> matching = new EldObjectArray<T>();
        final EldObjectArray<T> remaining = new EldObjectArray<T>();
        for (int index = 0; index < length; index++) {
            final T value = get(index);
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
        final Object[] values = new Object[length];
        for (int index = 0; index < values.length; index++) {
            values[index] = invokeIndexed(transform, elements[index], index);
        }
        return array(values, elementDescriptor);
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
            final @Nullable Object value = elements[left];
            elements[left] = elements[right];
            elements[right] = value;
        }
    }

    @EldApi
    public EldObjectArray<T> concat(final EldObjectArray<T> additional) {
        final EldObjectArray<T> result = copy();
        result.ensureCapacity(length + additional.length);
        System.arraycopy(
            additional.elements, 0, result.elements, length, additional.length
        );
        result.length += additional.length;
        return result;
    }

    @EldApi
    public EldObjectArray<T> union(final EldObjectArray<T> additional) {
        final EldObjectArray<T> result = new EldObjectArray<T>();
        for (int index = 0; index < length; index++) {
            final T value = get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        for (int index = 0; index < additional.length; index++) {
            final T value = additional.get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
    public EldObjectArray<T> distinct() {
        final EldObjectArray<T> result = new EldObjectArray<T>();
        for (int index = 0; index < length; index++) {
            final T value = get(index);
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
    public EldObjectArray<T> intersect(final EldObjectArray<T> additional) {
        final EldObjectArray<T> result = new EldObjectArray<T>();
        for (int index = 0; index < length; index++) {
            final T value = get(index);
            if (additional.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
    public EldObjectArray<T> subtract(final EldObjectArray<T> additional) {
        final EldObjectArray<T> result = new EldObjectArray<T>();
        for (int index = 0; index < length; index++) {
            final T value = get(index);
            if (!additional.contains(value) && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    @EldApi
    public EldObjectArray<T> difference(final EldObjectArray<T> additional) {
        final EldObjectArray<T> result = subtract(additional);
        for (int index = 0; index < additional.length; index++) {
            final T value = additional.get(index);
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
        Object result = initial;
        for (int index = 0; index < length; index++) {
            result = reducer.type().parameterCount() == 3
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
            final @Nullable Object value = elements[index];
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
    public EldObjectArray<T> sort() {
        final EldObjectArray<T> result = copy();
        result.sortInPlace();
        return result;
    }

    @EldApi
    public void sortInPlace() {
        Arrays.sort(elements, 0, length);
    }

    @EldApi
    public void sortInPlace(final MethodHandle comparator) throws Throwable {
        sortObjectArray(comparator);
    }

    @EldApi
    public EldObjectArray<T> sort(final MethodHandle comparator) throws Throwable {
        final EldObjectArray<T> result = copy();
        result.sortInPlace(comparator);
        return result;
    }

    private void sortObjectArray(final MethodHandle comparator) throws Throwable {
        try {
            Arrays.sort(elements, 0, length, (left, right) -> {
                try {
                    return (int) comparator.invoke(left, right);
                }
                catch (final RuntimeException | Error exception) {
                    throw exception;
                }
                catch (final Throwable exception) {
                    throw new ComparatorFailure(exception);
                }
            });
        }
        catch (final ComparatorFailure exception) {
            throw exception.getCause();
        }
    }

    private static final class ComparatorFailure extends RuntimeException {
        private ComparatorFailure(final Throwable cause) {
            super(cause);
        }
    }

    private static boolean test(final MethodHandle callback, final @Nullable Object value, final int index) throws Throwable {
        return callback.type().parameterCount() == 2
            ? (boolean) callback.invoke(value, index)
            : (boolean) callback.invoke(value);
    }

    @EldApi
    public EldArray<?> flatten(final String elementDescriptor) throws Throwable {
        return flatMap(ARRAY_IDENTITY, elementDescriptor);
    }

    private static EldArray<?> array(final Object[] values, final String elementDescriptor) {
        return EldArray
            .fromObjectArray(new EldObjectArray<>(values), elementDescriptor);
    }

    private static Object invokeIndexed(final MethodHandle callback, final @Nullable Object value, final int index) throws Throwable {
        return callback.type().parameterCount() == 2
            ? callback.invoke(value, index)
            : callback.invoke(value);
    }

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof EldObjectArray<?> other && Arrays
            .equals(elements, 0, length, other.elements, 0, other.length);
    }

    @Override
    @EldApi
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < length; i++) {
            result = 31 * result + Objects.hashCode(elements[i]);
        }
        return result;
    }

}
