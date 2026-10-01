package com.github.andreasarvidsson.eld.semantic;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.FunctionModifier;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;

public record FunctionSymbol(
    IdentifierDeclaration declaration, FunctionType type,
    List<@NonNull FunctionModifier> modifiers
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
        return format(
            type.parameterTypes().stream().map(Type::toString).toList()
        );
    }

    public String format(final List<String> parameterTypes) {
        return format(
            parameterTypes,
            Collections.nCopies(parameterTypes.size(), false)
        );
    }

    public String format(
        final List<String> parameterTypes,
        final List<Boolean> omittable
    ) {
        final String modifierText =
            modifiers.stream()
                .map(modifier -> modifier.value())
                .collect(Collectors.joining(" "));
        final String prefix = modifierText.isEmpty() ? "" : modifierText + " ";
        return prefix + "func " + formatSignature(parameterTypes, omittable);
    }

    public String formatParameterSignature(
        final List<String> parameterTypes,
        final List<Boolean> omittable
    ) {
        return formatParameterSignature(
            parameterTypes,
            omittable,
            Collections.nCopies(parameterTypes.size(), null)
        );
    }

    private String formatParameterSignature(
        final List<String> parameterTypes,
        final List<Boolean> omittable,
        final List<@Nullable String> defaults
    ) {
        return name() + "("
            + type.formatLabeledParameters(parameterTypes, omittable, defaults)
            + ")";
    }

    public String formatSignature(
        final List<String> parameterTypes,
        final List<Boolean> omittable
    ) {
        return formatSignature(
            parameterTypes,
            omittable,
            Collections.nCopies(parameterTypes.size(), null)
        );
    }

    public String formatSignature(
        final List<String> parameterTypes,
        final List<Boolean> omittable,
        final List<@Nullable String> defaults
    ) {
        return formatParameterSignature(parameterTypes, omittable, defaults)
            + " => " + type.returnType();
    }
}
