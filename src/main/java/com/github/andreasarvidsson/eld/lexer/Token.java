package com.github.andreasarvidsson.eld.lexer;

import java.util.Objects;

import com.github.andreasarvidsson.eld.Range;

public record Token(TokenType type, String text, Range range) {

    @Override
    public String toString() {
        final String typeText = switch (type()) {
            case IDENTIFIER, INTEGER_LITERAL, FLOAT_LITERAL, CHAR_LITERAL,
                STRING_LITERAL, RAW_STRING_LITERAL,
                BOOLEAN_LITERAL -> "%s \"%s\"".formatted(
                    type(),
                    text().replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                );

            default -> type().name();
        };

        final String result = "%s (%s)".formatted(typeText, range());
        return Objects.requireNonNull(result);
    }
}
