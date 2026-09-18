package com.github.andreasarvidsson.eld.semantic;

import java.util.List;

import org.jspecify.annotations.NonNull;

public record FunctionType(List<@NonNull Type> parameterTypes, Type returnType)
    implements Type {

    @Override
    public String toString() {
        return "%s => %s".formatted(
            parameterTypes.stream()
                .map(Type::toString)
                .collect(java.util.stream.Collectors.joining(", ", "(", ")")),
            returnType
        );
    }
}
