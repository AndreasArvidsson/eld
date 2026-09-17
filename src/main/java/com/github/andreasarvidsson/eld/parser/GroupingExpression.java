package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record GroupingExpression(Expression expression, Range range)
    implements Expression {
}
