package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import java.util.List;
import java.util.stream.IntStream;

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
        final List<FunctionParameter> parameters = declaration.parameters();
        final List<String> parameterTypes =
            IntStream.range(0, parameters.size()).mapToObj(i -> {
                final Type declared = declaredParameterTypes.get(i);
                final String text = declared.toString();
                return parameters.get(i).omittable()
                    && declared instanceof UnionType ? "(" + text + ")" : text;
            }).toList();
        return "constructor(%s)".formatted(
            FunctionType
                .declared(declaredParameterTypes, BuiltinType.VOID, parameters)
                .formatLabeledParameters(
                    parameterTypes,
                    parameters.stream()
                        .map(FunctionParameter::omittable)
                        .toList()
                )
        );
    }
}
