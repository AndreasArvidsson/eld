package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record SwitchBranchExpressionBody(Expression expression, Range range)
    implements SwitchBranchBody {
}
