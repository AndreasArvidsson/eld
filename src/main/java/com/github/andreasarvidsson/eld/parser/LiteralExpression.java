package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record LiteralExpression(LiteralKind kind, String text, Range range)
    implements Expression {
}
