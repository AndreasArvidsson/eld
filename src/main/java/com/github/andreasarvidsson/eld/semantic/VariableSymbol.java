package com.github.andreasarvidsson.eld.semantic;

import java.util.Locale;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.Mutability;

public record VariableSymbol(
    IdentifierDeclaration declaration, Type type, Mutability mutability
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
        return "%s %s: %s".formatted(
            mutability.toString().toLowerCase(Locale.ROOT),
            name(),
            type
        );
    }
}
