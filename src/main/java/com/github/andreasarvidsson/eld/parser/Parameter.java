package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record Parameter(
    IdentifierDeclaration name, @Nullable TypeNode type, Range range
) implements AstNode {
}
