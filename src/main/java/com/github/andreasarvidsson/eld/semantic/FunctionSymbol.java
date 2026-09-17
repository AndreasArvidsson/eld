package com.github.andreasarvidsson.eld.semantic;

import java.util.Objects;

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
        return Objects.requireNonNull(
            String.format(
                "FunctionSymbol(name=%s, parameterTypes=%s, returnType=%s)",
                name(),
                type.parameterTypes(),
                type.returnType()
            )
        );
    }
}
