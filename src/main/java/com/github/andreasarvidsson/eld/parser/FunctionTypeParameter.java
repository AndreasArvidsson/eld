package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record FunctionTypeParameter(
    @Nullable String label, TypeNode type, Range range
) implements AstNode {
}
