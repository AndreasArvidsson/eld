package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record TernaryExpression(
    Expression condition, Expression thenBranch, Expression elseBranch,
    Range range
) implements Expression {
}
