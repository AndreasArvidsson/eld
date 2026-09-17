package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record PostfixExpression(
    Expression operand, PostfixOperator operator, Range range
) implements Expression {
}
