package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record DeclarationStatement(Declaration declaration)
    implements Statement {
    @Override
    public Range range() {
        return declaration.range();
    }
}
