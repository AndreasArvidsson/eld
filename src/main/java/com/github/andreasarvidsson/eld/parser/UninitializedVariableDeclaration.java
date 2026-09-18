package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record UninitializedVariableDeclaration(
    Mutability mutability, IdentifierDeclaration name, TypeNode type,
    Range range
) implements Declaration, InterfaceMemberDeclaration {
}
