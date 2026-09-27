package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record ForStatement(
    @Nullable Statement initializer, @Nullable Expression condition,
    @Nullable Statement update, BlockStatement body, Range range
) implements Statement {
}
