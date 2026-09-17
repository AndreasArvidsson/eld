package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record AssignmentExpression(
    Expression target, Expression value, Range range
) implements Expression {
}
