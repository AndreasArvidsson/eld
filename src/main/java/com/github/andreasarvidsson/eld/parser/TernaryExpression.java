package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record TernaryExpression(
    Expression condition, Expression thenBranch, Expression elseBranch
) implements Expression {
    @Override
    public Range range() {
        return condition.range().union(elseBranch.range());
    }
}
