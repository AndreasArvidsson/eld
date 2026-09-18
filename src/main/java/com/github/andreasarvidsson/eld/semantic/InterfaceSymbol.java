package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;

import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;

public record InterfaceSymbol(
    IdentifierDeclaration declaration, InterfaceType type
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
        return "interface " + name();
    }
}
