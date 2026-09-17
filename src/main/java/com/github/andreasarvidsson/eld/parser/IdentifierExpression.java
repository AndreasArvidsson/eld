package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record IdentifierExpression(String name, Range range)
    implements Expression {
}
