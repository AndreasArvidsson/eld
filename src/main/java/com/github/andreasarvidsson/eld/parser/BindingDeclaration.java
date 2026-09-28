package com.github.andreasarvidsson.eld.parser;

public sealed interface BindingDeclaration extends Declaration
    permits IdentifierDeclaration, DiscardDeclaration {
}
