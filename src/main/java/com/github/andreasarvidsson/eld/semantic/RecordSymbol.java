package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.RecordDeclaration;
import java.util.List;
import java.util.stream.IntStream;

public final class RecordSymbol implements ClassDeclarationSymbol {
    private final RecordDeclaration declaration;
    private final ClassType type;
    private List<Type> componentTypes = List.of();

    public RecordSymbol(
        final RecordDeclaration declaration,
        final ClassType type
    ) {
        this.declaration = declaration;
        this.type = type;
    }

    public RecordDeclaration declaration() {
        return declaration;
    }

    @Override
    public ClassType type() {
        return type;
    }

    @Override
    public String name() {
        return declaration.name().name();
    }

    @Override
    public Range range() {
        return declaration.name().range();
    }

    public void setComponentTypes(final List<Type> types) {
        componentTypes = List.copyOf(types);
    }

    @Override
    public String toString() {
        final int namedOnlyStart =
            IntStream.range(0, declaration.parameters().size())
                .filter(i -> declaration.parameters().get(i).namedOnly())
                .findFirst()
                .orElse(-1);
        return "record %s(%s)".formatted(
            name(),
            FunctionType.formatParameters(
                IntStream.range(0, declaration.parameters().size())
                    .mapToObj(
                        i -> declaration.parameters().get(i).name().name()
                            + ": " + componentTypes.get(i)
                    )
                    .toList(),
                namedOnlyStart
            )
        );
    }
}
