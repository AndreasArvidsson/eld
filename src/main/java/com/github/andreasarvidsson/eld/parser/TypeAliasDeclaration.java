package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record TypeAliasDeclaration(
    IdentifierDeclaration name, TypeNode type, Range range
) implements Declaration {
}
