package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record FunctionParameter(IdentifierDeclaration name, TypeNode type)
    implements AstNode {

    @Override
    public Range range() {
        return name.range().union(type.range());
    }

}
