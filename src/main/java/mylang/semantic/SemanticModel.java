package mylang.semantic;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import mylang.parser.Declaration;
import mylang.parser.Expression;
import mylang.parser.IdentifierExpression;
import mylang.parser.TypeNode;

public final class SemanticModel {

    private final Map<Expression, Type> expressionTypes = new IdentityHashMap<>();
    private final Map<TypeNode, Type> resolvedTypes = new IdentityHashMap<>();
    private final Map<IdentifierExpression, Symbol> symbols = new IdentityHashMap<>();
    private final Map<Declaration, Symbol> declarations = new IdentityHashMap<>();

    public void setExpressionType(final Expression expression, final Type type) {
        expressionTypes.put(expression, type);
    }

    public @Nullable Type getExpressionType(final Expression expression) {
        return expressionTypes.get(expression);
    }

    public void setResolvedType(final TypeNode typeNode, final Type type) {
        resolvedTypes.put(typeNode, type);
    }

    public @Nullable Type getResolvedType(final TypeNode typeNode) {
        return resolvedTypes.get(typeNode);
    }

    public void setSymbol(
            final IdentifierExpression expression,
            final Symbol symbol) {
        symbols.put(expression, symbol);
    }

    public void setSymbol(
            final Declaration declaration,
            final Symbol symbol) {
        declarations.put(declaration, symbol);
    }

    public @Nullable Symbol getSymbol(final IdentifierExpression expression) {
        return symbols.get(expression);
    }

    public @Nullable Symbol getSymbol(final Declaration declaration) {
        return declarations.get(declaration);
    }

    @Override
    public String toString() {
        final String indent = "  ";
        final List<String> lines = new ArrayList<>();
        if (!expressionTypes.isEmpty()) {
            lines.add("Expression types:");
            for (final var entry : expressionTypes.entrySet()) {
                lines.add(indent + entry.getKey().range() + " -> " + entry.getValue());
            }
            lines.add("");
        }
        if (!resolvedTypes.isEmpty()) {
            lines.add("Resolved types:");
            for (final var entry : resolvedTypes.entrySet()) {
                lines.add(indent + entry.getKey().range() + " -> " + entry.getValue());
            }
            lines.add("");
        }
        if (!symbols.isEmpty()) {
            lines.add("Symbols:");
            for (final var entry : symbols.entrySet()) {
                lines.add(indent + entry.getKey().range() + " ->  " + entry.getValue());
            }
            lines.add("");
        }
        if (!declarations.isEmpty()) {
            lines.add("Declarations:");
            for (final var entry : declarations.entrySet()) {
                lines.add(indent + entry.getKey().range() + " -> " + entry.getValue());
            }
            lines.add("");
        }
        return Objects.requireNonNull(String.join("\n", lines).stripTrailing());
    }
}
