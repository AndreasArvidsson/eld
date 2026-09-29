package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.ClassModifier;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;

public record ClassSymbol(
    IdentifierDeclaration declaration, ClassType type,
    List<@NonNull ClassModifier> modifiers,
    @Nullable List<@NonNull IdentifierExpression> permittedSubclasses
) implements ClassDeclarationSymbol {

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
        final String modifierText =
            modifiers.stream()
                .map(modifier -> modifier.value())
                .collect(Collectors.joining(" "));
        final String prefix = modifierText.isEmpty() ? "" : modifierText + " ";
        final String permits =
            permittedSubclasses == null
                ? ""
                : permittedSubclasses.isEmpty()
                    ? " permits"
                    : " permits " + permittedSubclasses.stream()
                        .map(IdentifierExpression::name)
                        .collect(Collectors.joining(", "));
        return "%sclass %s%s".formatted(prefix, name(), permits);
    }

}
