package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;

public sealed interface BindingDeclaration extends Declaration
    permits IdentifierDeclaration, DiscardDeclaration {
    default @Nullable String label() {
        return this instanceof IdentifierDeclaration identifier
            ? identifier.name()
            : null;
    }
}
