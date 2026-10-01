package com.github.andreasarvidsson.eld.lexer;

import java.util.Objects;

import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.Position;
import org.jspecify.annotations.Nullable;

public record Token(
    TokenType type, String text, Range range, @Nullable String source
) {
    public Token(final TokenType type, final String text, final Range range) {
        this(type, text, range, null);
    }

    public @Nullable String sourceText(final Range sourceRange) {
        return source == null
            ? null
            : source.substring(
                offset(source, sourceRange.start()),
                offset(source, sourceRange.end())
            );
    }

    private static int offset(final String source, final Position position) {
        int start = 0;
        for (int line = 1; line < position.line(); line++) {
            start = source.indexOf('\n', start) + 1;
        }
        return start + position.column() - 1;
    }

    @Override
    public String toString() {
        final String typeText = switch (type()) {
            case IDENTIFIER, INTEGER_LITERAL, FLOAT_LITERAL, CHAR_LITERAL,
                STRING_LITERAL, RAW_STRING_LITERAL, FORMAT_STRING_TEXT,
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
