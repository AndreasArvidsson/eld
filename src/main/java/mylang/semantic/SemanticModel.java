package mylang.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import mylang.parser.AstNode;
import mylang.parser.Expression;
import mylang.parser.IdentifierDeclaration;
import mylang.parser.IdentifierExpression;
import mylang.parser.TypeNode;

public final class SemanticModel {

    private static final String ARROW = " -> ";
    private final Map<Expression, Type> expressionTypes = new IdentityHashMap<>();
    private final Map<TypeNode, Type> resolvedTypes = new IdentityHashMap<>();
    private final Map<IdentifierDeclaration, Symbol> declarations = new IdentityHashMap<>();
    private final Map<IdentifierExpression, Symbol> references = new IdentityHashMap<>();

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
            final IdentifierDeclaration declaration,
            final Symbol symbol) {
        declarations.put(declaration, symbol);
    }

    public @Nullable Symbol getSymbol(final IdentifierDeclaration declaration) {
        return declarations.get(declaration);
    }

    public void setReference(
            final IdentifierExpression expression,
            final Symbol symbol) {
        references.put(expression, symbol);
    }

    public @Nullable Symbol getReference(final IdentifierExpression expression) {
        return references.get(expression);
    }

    @Override
    public String toString() {
        final List<String> lines = new ArrayList<>();
        appendSection(lines, "Expression types:", expressionTypes);
        appendSection(lines, "Resolved types:", resolvedTypes);
        appendSection(lines, "Declarations:", declarations);

        if (!references.isEmpty()) {
            lines.add("References:");
            references.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> Objects.requireNonNull(entry.getKey()).range()))
                    .forEach(entry -> lines
                            .add("  " + Objects.requireNonNull(entry.getKey()).range() + ARROW
                                    + entry.getValue().range()));
            lines.add("");
        }

        return Objects.requireNonNull(String.join("\n", lines).stripTrailing());
    }

    private static void appendSection(
            final List<String> lines,
            final String heading,
            final Map<? extends AstNode, ?> entries) {
        if (entries.isEmpty()) {
            return;
        }
        lines.add(heading);
        entries.entrySet().stream()
                .sorted(Comparator.comparing(entry -> Objects.requireNonNull(entry.getKey()).range()))
                .forEach(entry -> lines
                        .add("  " + Objects.requireNonNull(entry.getKey()).range() + ARROW + entry.getValue()));
        lines.add("");
    }

}
