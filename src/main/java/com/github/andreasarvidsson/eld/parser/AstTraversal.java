package com.github.andreasarvidsson.eld.parser;

import java.util.function.Consumer;

/** Visits AST children in source order, including nested lambdas. */
public final class AstTraversal {
    public static void walk(
        final AstNode node,
        final Consumer<AstNode> visitor
    ) {
        visitor.accept(node);
        for (final var component : node.getClass().getRecordComponents()) {
            try {
                final Object value = component.getAccessor().invoke(node);
                if (value instanceof AstNode child) {
                    walk(child, visitor);
                }
                else if (value instanceof Iterable<?> children) {
                    for (final Object child : children) {
                        if (child instanceof AstNode ast) {
                            walk(ast, visitor);
                        }
                    }
                }
            }
            catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot walk AST", e);
            }
        }
    }

    private AstTraversal() {}
}
