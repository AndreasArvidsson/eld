package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record LambdaParameter(BindingDeclaration name) implements AstNode {

    public boolean discarded() {
        return name instanceof DiscardDeclaration;
    }

    @Override
    public Range range() {
        return name.range();
    }

}
