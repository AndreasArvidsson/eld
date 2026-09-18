package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record LambdaParameter(IdentifierDeclaration name) implements AstNode {

    @Override
    public Range range() {
        return name.range();
    }

}
