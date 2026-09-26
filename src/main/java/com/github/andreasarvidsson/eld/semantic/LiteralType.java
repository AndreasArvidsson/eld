package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.LiteralKind;

public record LiteralType(LiteralKind kind, String text) implements Type {

    public LiteralType {
        if (
            kind != LiteralKind.STRING && kind != LiteralKind.BOOL
                && kind != LiteralKind.INT
        ) {
            throw new IllegalArgumentException(
                "Unsupported literal type: " + kind
            );
        }
    }

    public BuiltinType valueType() {
        return switch (kind) {
            case STRING -> BuiltinType.STRING;
            case BOOL -> BuiltinType.BOOL;
            case INT -> BuiltinType.I32;
            default -> throw new IllegalStateException();
        };
    }

    public static Type unwrap(final Type type) {
        return type instanceof LiteralType literal ? literal.valueType() : type;
    }

    public Object value() {
        return switch (kind) {
            case STRING -> decodeString(text);
            case BOOL -> Boolean.valueOf(text);
            case INT -> new BigInteger(text.replace("_", ""));
            default -> throw new IllegalStateException();
        };
    }

    private static String decodeString(final String text) {
        final StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (
                c == '\\' && i + 1 < text.length() - 1
                    && "btnfr0'\"\\".indexOf(text.charAt(i + 1)) >= 0
            ) {
                c = switch (text.charAt(++i)) {
                    case 'b' -> '\b';
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'f' -> '\f';
                    case 'r' -> '\r';
                    case '0' -> '\0';
                    default -> text.charAt(i);
                };
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return other instanceof LiteralType literal && kind == literal.kind
            && value().equals(literal.value());
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, value());
    }
}
