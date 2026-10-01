package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import java.util.List;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

public record ConstructorSymbol(
    ConstructorDeclaration declaration, FunctionType type,
    List<Type> declaredParameterTypes
) implements Symbol {

    @Override
    public String name() {
        return "constructor";
    }

    @Override
    public Range range() {
        return declaration.range();
    }

    @Override
    public String toString() {
        return formatSignature(false);
    }

    public String formatSignature(final boolean showDefaults) {
        final List<FunctionParameter> parameters = declaration.parameters();
        final List<String> parameterTypes =
            IntStream.range(0, parameters.size()).mapToObj(i -> {
                final Type declared = declaredParameterTypes.get(i);
                return FunctionSignatures.formatParameterType(
                    declared,
                    parameters.get(i),
                    showDefaults
                );
            }).toList();
        return "constructor(%s)".formatted(
            FunctionType
                .declared(declaredParameterTypes, BuiltinType.VOID, parameters)
                .formatLabeledParameters(
                    parameterTypes,
                    parameters.stream()
                        .map(FunctionParameter::omittable)
                        .toList(),
                    parameters.stream()
                        .<@Nullable String>map(
                            parameter -> showDefaults
                                ? parameter.defaultText()
                                : null
                        )
                        .toList()
                )
        );
    }
}
