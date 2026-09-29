package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.EnumDeclaration;

public record EnumSymbol(EnumDeclaration declaration, ClassType type)
    implements ClassDeclarationSymbol {

    @Override
    public String name() {
        return declaration.name().name();
    }

    @Override
    public Range range() {
        return declaration.name().range();
    }

    @Override
    public String toString() {
        return "enum " + name();
    }
}
