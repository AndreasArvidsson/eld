package com.github.andreasarvidsson.eld.parser;

public sealed interface Declaration extends BlockItem
    permits VariableDeclaration, ClassDeclaration, FunctionDeclaration,
    IdentifierDeclaration, ConstructorDeclaration,
    UninitializedVariableDeclaration, InterfaceDeclaration {
}
