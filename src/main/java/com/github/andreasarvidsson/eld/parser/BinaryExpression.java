package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record BinaryExpression(
    Expression left, BinaryOperator operator, Expression right, Range range
) implements Expression {
}
