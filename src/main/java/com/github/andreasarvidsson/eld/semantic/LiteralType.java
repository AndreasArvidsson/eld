package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.StringLiterals;
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
            case STRING -> StringLiterals.decode(text);
            case BOOL -> Boolean.valueOf(text);
            case INT -> new BigInteger(text.replace("_", ""));
            default -> throw new IllegalStateException();
        };
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
