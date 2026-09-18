package com.github.andreasarvidsson.eld.parser;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

public class AstPrinter {
    public static String print(final AstNode node) {
        final StringBuilder output = new StringBuilder();
        append(output, node, 0);
        return Objects.requireNonNull(output.toString());
    }

    private static void append(
        final StringBuilder output,
        final @Nullable Object value,
        final int depth
    ) {
        if (value instanceof AstNode node) {
            appendNode(output, node, depth);
        }
        else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                newline(output, depth + 1);
                append(output, list.get(i), depth + 1);
            }
        }
        else if (value instanceof String text) {
            appendString(output, text);
        }
        else {
            // Keep ranges, enums and null compact.
            output.append(value);
        }
    }

    private static void appendNode(
        final StringBuilder output,
        final AstNode node,
        final int depth
    ) {
        final Class<?> type = node.getClass();

        if (!type.isRecord()) {
            throw new IllegalArgumentException(
                "Expected an AST record: " + type.getName()
            );
        }

        final RecordComponent[] components =
            Objects.requireNonNull(type.getRecordComponents());
        output.append("(").append(type.getSimpleName());

        newline(output, depth + 1);
        output.append("range: ");
        append(output, node.range(), depth);

        for (final RecordComponent component : components) {
            if (component.getName().equals("range")) {
                continue;
            }

            try {
                final Object value = component.getAccessor().invoke(node);
                if (value instanceof List<?>) {
                    append(output, value, depth);
                }
                else {
                    newline(output, depth + 1);
                    if (!(value instanceof AstNode)) {
                        output.append(component.getName()).append(": ");
                    }
                    append(output, value, depth + 1);
                }
            }
            catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "Cannot read " + type.getSimpleName() + "."
                        + component.getName(),
                    e
                );
            }
        }

        newline(output, depth);
        output.append(")");
    }

    private static void appendString(
        final StringBuilder output,
        final String text
    ) {
        output.append('"');
        for (int i = 0; i < text.length(); i++) {
            final char character = text.charAt(i);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        output.append("\\u%04x".formatted((int) character));
                    }
                    else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static void newline(final StringBuilder output, final int depth) {
        output.append('\n').append("  ".repeat(depth));
    }
}
