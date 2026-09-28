package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record VariableDeclaration(
    Mutability mutability, BindingDeclaration name, @Nullable TypeNode type,
    Expression initializer, Range range
) implements Declaration {
}
