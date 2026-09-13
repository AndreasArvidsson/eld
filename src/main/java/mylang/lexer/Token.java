package mylang.lexer;

import java.util.Objects;

public record Token(TokenType type, String text, Range range) {

    public String toString() {
        final String typeText = switch (type()) {
            case IDENTIFIER,
                    INTEGER_LITERAL,
                    FLOAT_LITERAL,
                    STRING_LITERAL,
                    BOOLEAN_LITERAL ->
                String.format("%s \"%s\"", type(), text());
            default -> type().name();
        };
        final String result = String.format("%s (%s)", typeText, range());
        return Objects.requireNonNull(result);
    }
}
