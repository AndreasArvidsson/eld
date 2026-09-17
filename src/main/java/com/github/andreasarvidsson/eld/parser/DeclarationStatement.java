package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record DeclarationStatement(Declaration declaration, Range range)
    implements Statement {
}
