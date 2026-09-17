package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record SwitchBranchExpressionBody(Expression expression)
    implements SwitchBranchBody {
    @Override
    public Range range() {
        return expression.range();
    }
}
