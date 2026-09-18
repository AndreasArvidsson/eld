package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public record InterfaceType(
    String name, List<Type> typeArguments, @Nullable Class<?> javaClass
) implements Type {

    public InterfaceType(String name) {
        this(name, List.of(), null);
    }

    @Override
    public String toString() {
        return typeArguments.isEmpty()
            ? name
            : name + typeArguments.stream()
                .map(Object::toString)
                .collect(Collectors.joining(", ", "<", ">"));
    }
}
