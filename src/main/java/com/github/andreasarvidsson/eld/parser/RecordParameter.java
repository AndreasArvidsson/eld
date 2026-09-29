package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record RecordParameter(
    IdentifierDeclaration name, TypeNode type, boolean namedOnly
) implements AstNode {
    public RecordParameter(IdentifierDeclaration name, TypeNode type) {
        this(name, type, false);
    }

    @Override
    public Range range() {
        return name.range().union(type.range());
    }
}
