package mylang.lexer;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public class Lexer {
    private final String source;
    private int position, line, column;

    public Lexer(final String source) {
        this.source = source;
        this.position = 0;
        this.line = 1;
        this.column = 1;
    }

    public @Nullable Token nextToken() {
        final Character next = peek();

        if (next == null) {
            return null;
        }

        if (next == '\0') {
            advance();
            return createToken(TokenType.EOF, "");
        }

        if (Character.isDigit(next)) {
            return readNumber();
        }

        if (Character.isWhitespace(next)) {
            skipWhitespace();
            return nextToken();
        }

        if (next == '_' || Character.isAlphabetic(next)) {
            return readIdentifier();
        }

        switch (next) {
            case '=':
                advance();
                final Character next2 = peek();
                if (next2 != null && next2 == '=') {
                    advance();
                    return createToken(TokenType.EQUAL_EQUAL, "==");
                }
                return createToken(TokenType.EQUAL, "=");
        }

        throw new RuntimeException(
                String.format("Unexpected character '%c' at line %d, column %d", next, line, column));
    }

    private void skipWhitespace() {
        while (true) {
            final Character next = peek();

            if (next == null || !Character.isWhitespace(next)) {
                return;
            }

            advance();

            if (next == '\n') {
                line++;
                column = 1;
            }
        }
    }

    private Token readIdentifier() {
        final StringBuilder builder = new StringBuilder();
        while (true) {
            final Character next = peek();
            if (next == null || !(next == '_' || Character.isAlphabetic(next))) {
                break;
            }
            builder.append(next);
            advance();
        }
        final String text = Objects.requireNonNull(builder.toString());

        switch (text) {
            case "const":
                return createToken(TokenType.CONST, text);
            case "var":
                return createToken(TokenType.VAR, text);
            case "if":
                return createToken(TokenType.IF, text);
            case "elif":
                return createToken(TokenType.ELIF, text);
            case "else":
                return createToken(TokenType.ELSE, text);
            case "while":
                return createToken(TokenType.WHILE, text);
            case "for":
                return createToken(TokenType.FOR, text);
            case "return":
                return createToken(TokenType.RETURN, text);
        }

        return createToken(TokenType.IDENTIFIER, text);
    }

    private Token readNumber() {
        final StringBuilder builder = new StringBuilder();
        while (true) {
            final Character next = peek();
            if (next == null || !Character.isDigit(next)) {
                break;
            }
            builder.append(next);
            advance();
        }
        final String text = Objects.requireNonNull(builder.toString());
        return createToken(TokenType.INTEGER_LITERAL, text);
    }

    private Token createToken(final TokenType type, final String text) {
        final Position start = new Position(line, column - text.length());
        final Position end = new Position(line, column);
        final Range range = new Range(start, end);
        return new Token(type, text, range);
    }

    private @Nullable Character peek() {
        if (position >= source.length()) {
            return null;
        }
        return source.charAt(position);
    }

    private void advance() {
        position++;
        column++;
    }

}
