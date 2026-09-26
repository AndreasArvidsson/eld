package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.FunctionModifier;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public record FunctionSymbol(
    IdentifierDeclaration declaration, FunctionType type,
    List<FunctionModifier> modifiers
) implements Symbol {

    public FunctionSymbol(
        final IdentifierDeclaration declaration,
        final FunctionType type
    ) {
        this(declaration, type, List.of());
    }

    public FunctionSymbol {
        modifiers = List.copyOf(modifiers);
    }

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
                .map(modifier -> modifier.toString().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
        final String prefix =
            modifierText.isEmpty() ? "func " : modifierText + " func ";
        return "%s%s(%s) => %s".formatted(
            prefix,
            name(),
            type.parameterTypes()
                .stream()
                .map(Object::toString)
                .collect(Collectors.joining(", ")),
            type.returnType()
        );
    }
}
