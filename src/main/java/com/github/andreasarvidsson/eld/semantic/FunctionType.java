package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;

public record FunctionType(
    List<@NonNull Type> parameterTypes, Type returnType,
    List<@Nullable String> parameterNames, int namedOnlyStart
) implements Type {
    public FunctionType(
        final List<@NonNull Type> parameterTypes,
        final Type returnType
    ) {
        this(parameterTypes, returnType, List.of(), -1);
    }

    public static FunctionType declared(
        final List<@NonNull Type> parameterTypes,
        final Type returnType,
        final List<@NonNull FunctionParameter> parameters
    ) {
        int namedOnlyStart = -1;
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).namedOnly()) {
                namedOnlyStart = i;
                break;
            }
        }
        final List<@Nullable String> parameterNames = new ArrayList<>();
        for (final FunctionParameter parameter : parameters) {
            parameterNames.add(parameter.name().label());
        }
        return new FunctionType(
            parameterTypes,
            returnType,
            parameterNames,
            namedOnlyStart
        );
    }

    public String formatParameters(final List<String> parameters) {
        return formatParameters(parameters, namedOnlyStart);
    }

    public boolean matchesSignature(final FunctionType other) {
        return parameterTypes.equals(other.parameterTypes())
            && returnType.equals(other.returnType())
            && namedOnlyStart == other.namedOnlyStart();
    }

    public boolean sameOverloadSignature(final FunctionType other) {
        if (
            !parameterTypes.equals(other.parameterTypes())
                || namedOnlyStart != other.namedOnlyStart()
        ) {
            return false;
        }
        for (int i = namedOnlyStart; i >= 0 && i < parameterTypes.size(); i++) {
            final @Nullable String label =
                parameterNames.isEmpty() ? null : parameterNames.get(i);
            final @Nullable String otherLabel =
                other.parameterNames().isEmpty()
                    ? null
                    : other.parameterNames().get(i);
            if (!Objects.equals(label, otherLabel)) {
                return false;
            }
        }
        return true;
    }

    public String formatLabeledParameters(final List<String> parameters) {
        return formatLabeledParameters(
            parameters,
            Collections.nCopies(parameters.size(), false)
        );
    }

    public String formatLabeledParameters(
        final List<String> parameters,
        final List<Boolean> omittable
    ) {
        final List<String> labeled = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            final @Nullable String label =
                parameterNames.isEmpty() ? null : parameterNames.get(i);
            final String parameter = parameters.get(i);
            labeled
                .add(
                    label == null
                        ? parameter + (omittable.get(i) ? "?" : "")
                        : label + (omittable.get(i) ? "?" : "") + ": "
                            + parameter
                );
        }
        return formatParameters(labeled);
    }

    public static String formatParameters(
        final List<String> parameters,
        final int namedOnlyStart
    ) {
        final List<String> parts = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            if (i == namedOnlyStart) {
                parts.add("*");
            }
            parts.add(parameters.get(i));
        }
        return String.join(", ", parts);
    }

    @Override
    public String toString() {
        return "%s => %s".formatted(
            "(" + formatLabeledParameters(
                parameterTypes.stream().map(Type::toString).toList()
            ) + ")",
            returnType
        );
    }
}
