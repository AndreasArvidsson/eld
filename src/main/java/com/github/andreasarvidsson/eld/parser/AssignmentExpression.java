package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record AssignmentExpression(Expression target, Expression value)
    implements Expression {
    @Override
    public Range range() {
        return target.range().union(value.range());
    }
}
