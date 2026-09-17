package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;
import org.jspecify.annotations.Nullable;

public record Parameter(IdentifierDeclaration name, @Nullable TypeNode type)
    implements AstNode {
    @Override
    public Range range() {
        return type == null ? name.range() : name.range().union(type.range());
    }
}
