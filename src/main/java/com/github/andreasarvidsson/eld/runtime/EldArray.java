package com.github.andreasarvidsson.eld.runtime;

import java.lang.reflect.Array;

public interface EldArray<T extends EldArray<T>> {
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
        final Object values = Array.newInstance(component, objects.length());
        for (int i = 0; i < objects.length(); i++) {
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

    int length();

    boolean isEmpty();

    void clear();

    T copy();

    T sliceFrom(int start);

    T sliceTo(int end);

    T slice(int start, int end);

    int normalizeIndex(int index);
}
