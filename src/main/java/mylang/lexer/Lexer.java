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

        if (Character.isDigit(next)) {
            return readNumberLiteral();
        }

        if (Character.isWhitespace(next)) {
            skipWhitespace();
            return nextToken();
        }

        if (next == '_' || Character.isAlphabetic(next)) {
            return readIdentifier();
        }

        switch (next) {
            case '\0':
                advance();
                return createToken(TokenType.EOF, "");
            case '-':
                advance();
                return createToken(TokenType.MINUS, next);
            case '+':
                advance();
                return createToken(TokenType.PLUS, next);
            case '*':
                advance();
                return createToken(TokenType.STAR, next);
            case '/':
                advance();
                return createToken(TokenType.SLASH, next);
            case '\'':
                return readCharLiteral();
            case '"':
                return readStringLiteral();
            case ':':
                advance();
                return createToken(TokenType.COLON, next);
            case ',':
                advance();
                return createToken(TokenType.COMMA, next);
            case '(':
                advance();
                return createToken(TokenType.LEFT_PAREN, next);
            case ')':
                advance();
                return createToken(TokenType.RIGHT_PAREN, next);
            case '{':
                advance();
                return createToken(TokenType.LEFT_BRACE, next);
            case '}':
                advance();
                return createToken(TokenType.RIGHT_BRACE, next);
            case '[':
                advance();
                return createToken(TokenType.LEFT_BRACKET, next);
            case ']':
                advance();
                return createToken(TokenType.RIGHT_BRACKET, next);

            case '=': {
                advance();
                final Character next2 = peek();
                if (next2 != null) {
                    if (next2 == '=') {
                        advance();
                        return createToken(TokenType.EQUAL_EQUAL, next, next2);
                    }
                    if (next2 == '>') {
                        advance();
                        return createToken(TokenType.FAT_ARROW, next, next2);
                    }
                }
                return createToken(TokenType.EQUAL, next);
            }

            case '!': {
                advance();
                final Character next2 = peek();
                if (next2 != null && next2 == '=') {
                    advance();
                    return createToken(TokenType.BANG_EQUAL, next, next2);
                }
                return createToken(TokenType.BANG, next);
            }

            case '<': {
                advance();
                final Character next2 = peek();
                if (next2 != null && next2 == '=') {
                    advance();
                    return createToken(TokenType.LESS_EQUAL, next, next2);
                }
                return createToken(TokenType.LESS, next);
            }

            case '>': {
                advance();
                final Character next2 = peek();
                if (next2 != null && next2 == '=') {
                    advance();
                    return createToken(TokenType.GREATER_EQUAL, next, next2);
                }
                return createToken(TokenType.GREATER, next);
            }
        }

        throw new LexerException(
                new Range(line, column, line, column + 1),
                "Unexpected character '%c'", next);
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
            case "func":
                return createToken(TokenType.FUNC, text);
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
            case "true":
            case "false":
                return createToken(TokenType.BOOLEAN_LITERAL, text);
        }

        return createToken(TokenType.IDENTIFIER, text);
    }

    private Token readNumberLiteral() {
        final StringBuilder builder = new StringBuilder();

        while (true) {
            final Character next = peek();
            if (next == null || !Character.isDigit(next)) {
                break;
            }
            builder.append(next);
            advance();
        }

        TokenType type = TokenType.INTEGER_LITERAL;
        final Character next = peek();

        if (next != null && next == '.' && position + 1 < source.length()
                && Character.isDigit(source.charAt(position + 1))) {
            type = TokenType.FLOAT_LITERAL;
            builder.append(next);
            advance();

            while (true) {
                final Character digit = peek();
                if (digit == null || !Character.isDigit(digit)) {
                    break;
                }
                builder.append(digit);
                advance();
            }
        }

        final String text = Objects.requireNonNull(builder.toString());
        return createToken(type, text);
    }

    private Token readStringLiteral() {
        // Skip the opening double quote.
        advance();

        final StringBuilder builder = new StringBuilder();
        while (true) {
            final Character next = peek();
            if (next == null || next == '"') {
                break;
            }
            builder.append(next);
            advance();
        }

        if (peek() == null || peek() != '"') {
            throw new LexerException(new Range(line, column, line, column + 1),
                    "Unterminated string literal");
        }

        // Skip the closing double quote.
        advance();

        final String text = Objects.requireNonNull(String.format("\"%s\"", builder.toString()));
        return createToken(TokenType.STRING_LITERAL, text);
    }

    private Token readCharLiteral() {
        // Skip the opening single quote.
        advance();

        final Character value = peek();

        if (value == null || value == '\'') {
            throw new LexerException(new Range(line, column, line, column + 1),
                    "Empty character literal");
        }

        advance();

        if (peek() == null || peek() != '\'') {
            throw new LexerException(new Range(line, column, line, column + 1),
                    "Character literal must contain exactly one character");
        }

        advance();

        final String text = Objects.requireNonNull(String.format("'%c'", value));
        return createToken(TokenType.CHAR_LITERAL, text);
    }

    private Token createToken(final TokenType type, final String text) {
        final Position start = new Position(line, column - text.length());
        final Position end = new Position(line, column);
        final Range range = new Range(start, end);
        return new Token(type, text, range);
    }

    private Token createToken(final TokenType type, final char c) {
        return createToken(type, Objects.requireNonNull(String.valueOf(c)));
    }

    private Token createToken(final TokenType type, final char c, final char c2) {
        return createToken(type, String.valueOf(c) + String.valueOf(c2));
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
