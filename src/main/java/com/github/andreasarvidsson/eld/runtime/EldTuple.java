package com.github.andreasarvidsson.eld.runtime;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class EldTuple {
    private final @Nullable Object[] elements;

    public EldTuple(final @Nullable Object[] elements) {
        this.elements = elements;
    }

    public @Nullable Object get(final int index) {
        return elements[index];
    }

    @Override
    public boolean equals(final @Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EldTuple other)) {
            return false;
        }
        return Arrays.equals(elements, other.elements);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(elements);
    }

    @Override
    public String toString() {
        return Arrays.stream(elements)
            .map(String::valueOf)
            .collect(Collectors.joining(", ", "(", ")"));
    }
}
