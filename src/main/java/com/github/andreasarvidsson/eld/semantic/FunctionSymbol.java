package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;

public record FunctionSymbol(
    IdentifierDeclaration declaration, FunctionType type
) implements Symbol {

    @Override
    public String name() {
        return declaration.name();
    }

    @Override
    public Range range() {
        return declaration.range();
    }

    @Override
    public String toString() {
        return "%s(%s) => %s".formatted(
            name(),
            type.parameterTypes()
                .stream()
                .map(Object::toString)
                .collect(java.util.stream.Collectors.joining(", ")),
            type.returnType()
        );
    }
}
