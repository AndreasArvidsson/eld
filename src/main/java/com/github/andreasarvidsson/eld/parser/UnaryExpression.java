package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record UnaryExpression(
    UnaryOperator operator, Expression operand, Range range
) implements Expression {
}
