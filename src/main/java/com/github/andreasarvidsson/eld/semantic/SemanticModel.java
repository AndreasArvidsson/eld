package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.TypeNode;

public final class SemanticModel {

    private static final String ARROW = " -> ";
    private final IdentityHashMap<Expression, Type> expressionTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> conversionTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<TypeNode, Type> resolvedTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<IdentifierDeclaration, Symbol> declarations =
        new IdentityHashMap<>();
    private final IdentityHashMap<IdentifierExpression, Symbol> references =
        new IdentityHashMap<>();

    public void setExpressionType(
        final Expression expression,
        final Type type
    ) {
        expressionTypes.put(expression, type);
    }

    public Type getExpressionType(final Expression expression) {
        return Objects.requireNonNull(expressionTypes.get(expression));
    }

    public void setConversionType(
        final Expression expression,
        final Type type
    ) {
        conversionTypes.put(expression, type);
    }

    public Type getConversionType(final Expression expression) {
        return Objects.requireNonNull(conversionTypes.get(expression));
    }

    public Type getEffectiveType(final Expression expression) {
        return conversionTypes
            .getOrDefault(expression, getExpressionType(expression));
    }

    public void setResolvedType(final TypeNode typeNode, final Type type) {
        resolvedTypes.put(typeNode, type);
    }

    public Type getResolvedType(final TypeNode typeNode) {
        return Objects.requireNonNull(resolvedTypes.get(typeNode));
    }

    public void setSymbol(
        final IdentifierDeclaration declaration,
        final Symbol symbol
    ) {
        declarations.put(declaration, symbol);
    }

    public Symbol getSymbol(final IdentifierDeclaration declaration) {
        return Objects.requireNonNull(declarations.get(declaration));
    }

    public void setReference(
        final IdentifierExpression expression,
        final Symbol symbol
    ) {
        references.put(expression, symbol);
    }

    public Symbol getReference(final IdentifierExpression expression) {
        return Objects.requireNonNull(references.get(expression));
    }

    @Override
    public String toString() {
        final List<String> lines = new ArrayList<>();
        appendSection(lines, "Expression types:", expressionTypes);
        appendSection(lines, "Resolved types:", resolvedTypes);

        appendSection(
            lines,
            "Conversions:",
            conversionTypes,
            (expression, type) -> expressionTypes.get(expression) + ARROW + type
        );
        appendSection(lines, "Declarations:", declarations);
        appendSection(
            lines,
            "References:",
            references,
            (expression, symbol) -> symbol instanceof BuiltinFunctionSymbol
                ? "builtin " + symbol.name()
                : symbol.range().toString()
        );
        return Objects.requireNonNull(String.join("\n", lines).stripTrailing());
    }

    private static void appendSection(
        final List<String> lines,
        final String heading,
        final IdentityHashMap<? extends AstNode, ?> entries
    ) {
        appendSection(
            lines,
            heading,
            entries,
            (node, value) -> String.valueOf(value)
        );
    }

    private static <K extends AstNode, V> void appendSection(
        final List<String> lines,
        final String heading,
        final IdentityHashMap<K, V> entries,
        final BiFunction<K, V, String> formatValue
    ) {
        if (entries.isEmpty()) {
            return;
        }
        lines.add(heading);
        entries.entrySet()
            .stream()
            .sorted(
                Comparator.comparing(
                    entry -> Objects.requireNonNull(entry.getKey()).range()
                )
            )
            .forEach(
                entry -> lines
                    .add(
                        "  " + Objects.requireNonNull(entry.getKey()).range()
                            + ARROW
                            + formatValue
                                .apply(entry.getKey(), entry.getValue())
                    )
            );
        lines.add("");
    }

}
