package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record ForEachStatement(
    IdentifierDeclaration value, @Nullable IdentifierDeclaration index,
    Expression iterable, BlockStatement body, Range range
) implements Statement {
}
