package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record AssignmentStatement(
    Expression target, AssignmentOperator operator, Expression value,
    Range range
) implements Statement {
}
