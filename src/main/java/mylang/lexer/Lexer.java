package mylang.lexer;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Objects.requireNonNull(Map.ofEntries(
            Map.entry("const", TokenType.CONST),
            Map.entry("var", TokenType.VAR),
            Map.entry("func", TokenType.FUNC),
            Map.entry("if", TokenType.IF),
            Map.entry("elif", TokenType.ELIF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("while", TokenType.WHILE),
            Map.entry("for", TokenType.FOR),
            Map.entry("return", TokenType.RETURN),
            Map.entry("true", TokenType.BOOLEAN_LITERAL),
            Map.entry("false", TokenType.BOOLEAN_LITERAL)));

    private static final Map<Character, TokenType> SYMBOLS = Objects.requireNonNull(Map.ofEntries(
            Map.entry('\0', TokenType.EOF),
            Map.entry('-', TokenType.MINUS),
            Map.entry('+', TokenType.PLUS),
            Map.entry('*', TokenType.STAR),
            Map.entry('/', TokenType.SLASH),
            Map.entry(':', TokenType.COLON),
            Map.entry(',', TokenType.COMMA),
            Map.entry('(', TokenType.LEFT_PAREN),
            Map.entry(')', TokenType.RIGHT_PAREN),
            Map.entry('{', TokenType.LEFT_BRACE),
            Map.entry('}', TokenType.RIGHT_BRACE),
            Map.entry('[', TokenType.LEFT_BRACKET),
            Map.entry(']', TokenType.RIGHT_BRACKET)));

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

        final TokenType symbolType = SYMBOLS.get(next);

        if (symbolType != null) {
            advance();
            return createToken(symbolType, next);
        }

        switch (next) {
            case '\'':
                return readCharLiteral();
            case '"':
                return readStringLiteral();

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
        final TokenType type = Objects.requireNonNull(KEYWORDS.getOrDefault(text, TokenType.IDENTIFIER));
        return createToken(type, text);
    }

    private Token readNumberLiteral() {
        final StringBuilder builder = new StringBuilder();
        readDigits(builder);

        TokenType type = TokenType.INTEGER_LITERAL;
        final Character next = peek();

        if (next != null && next == '.' && position + 1 < source.length()
                && Character.isDigit(source.charAt(position + 1))) {
            type = TokenType.FLOAT_LITERAL;
            builder.append(next);
            advance();

            readDigits(builder);
        }

        final String text = Objects.requireNonNull(builder.toString());
        return createToken(type, text);
    }

    private void readDigits(final StringBuilder builder) {
        while (true) {
            final Character next = peek();
            if (next == null) {
                return;
            }
            if (next == '_') {
                if (position + 1 >= source.length()
                        || !Character.isDigit(source.charAt(position + 1))) {
                    return;
                }
            } else if (!Character.isDigit(next)) {
                return;
            }
            builder.append(next);
            advance();
        }
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
