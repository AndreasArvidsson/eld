package com.github.andreasarvidsson.eld.semantic;

import java.util.List;

public record TupleType(List<Type> elementTypes) implements Type {

    @Override
    public String toString() {
        return elementTypes.stream()
            .map(Type::toString)
            .collect(java.util.stream.Collectors.joining(", ", "(", ")"));
    }
}
