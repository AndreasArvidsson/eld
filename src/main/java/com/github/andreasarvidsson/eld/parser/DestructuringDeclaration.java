package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record DestructuringDeclaration(
    Mutability mutability, Pattern pattern, Expression initializer, Range range
) implements Declaration {
}
