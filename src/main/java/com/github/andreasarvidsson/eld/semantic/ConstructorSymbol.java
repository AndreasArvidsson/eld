package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import java.util.stream.IntStream;

public record ConstructorSymbol(
    ConstructorDeclaration declaration, FunctionType type
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
        return "constructor(%s)".formatted(
            IntStream.range(0, declaration.parameters().size())
                .mapToObj(
                    i -> declaration.parameters().get(i).name().name() + ": "
                        + type.parameterTypes().get(i)
                )
                .collect(java.util.stream.Collectors.joining(", "))
        );
    }
}
