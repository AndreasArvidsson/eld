package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record NamedArgumentExpression(
    IdentifierDeclaration name, Expression value
) implements Expression {

    @Override
    public Range range() {
        return name.range().union(value.range());
    }
}
