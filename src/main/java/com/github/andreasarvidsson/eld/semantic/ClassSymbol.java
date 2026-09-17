package com.github.andreasarvidsson.eld.semantic;

import java.util.Objects;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;

public record ClassSymbol(IdentifierDeclaration declaration, ClassType type)
    implements Symbol {

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
            String.format("ClassSymbol(name=%s, type=%s)", name(), type)
        );
    }

}
