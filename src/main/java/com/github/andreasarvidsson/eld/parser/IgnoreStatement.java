package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record IgnoreStatement(Expression expression, Range range)
    implements Statement {
}
