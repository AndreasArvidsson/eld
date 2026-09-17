package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;
import org.jspecify.annotations.Nullable;

public record SliceExpression(
    Expression target, @Nullable Expression startIndex,
    @Nullable Expression endIndex, Range range
) implements Expression {
}
