package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;

import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;

public record InterfaceSymbol(
    IdentifierDeclaration declaration, InterfaceType type,
    @Nullable List<@NonNull IdentifierExpression> permittedSubclasses
) implements Symbol {
    @Override
    public String name() {
        return declaration.name();
    }
    @Override
    public Range range() {
        return declaration.range();
    }
    @Override
    public String toString() {
        final String permits =
            permittedSubclasses == null
                ? ""
                : permittedSubclasses.isEmpty()
                    ? " permits"
                    : " permits " + permittedSubclasses.stream()
                        .map(IdentifierExpression::name)
                        .collect(Collectors.joining(", "));
        return "interface " + name() + permits;
    }
}
