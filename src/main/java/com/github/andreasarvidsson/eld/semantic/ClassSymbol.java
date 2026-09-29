package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.ClassModifier;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;

public record ClassSymbol(
    IdentifierDeclaration declaration, ClassType type,
    List<@NonNull ClassModifier> modifiers
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
        return "%sclass %s".formatted(prefix, name());
    }

}
