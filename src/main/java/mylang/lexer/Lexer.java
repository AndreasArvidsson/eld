package mylang.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import mylang.Position;
import mylang.Range;

public class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Objects.requireNonNull(Map.ofEntries(
            Map.entry("const", TokenType.CONST),
            Map.entry("var", TokenType.VAR),
            Map.entry("func", TokenType.FUNC),
            Map.entry("if", TokenType.IF),
            Map.entry("elif", TokenType.ELIF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("do", TokenType.DO),
            Map.entry("while", TokenType.WHILE),
            Map.entry("for", TokenType.FOR),
            Map.entry("return", TokenType.RETURN),
            Map.entry("break", TokenType.BREAK),
            Map.entry("continue", TokenType.CONTINUE),
            Map.entry("null", TokenType.NULL),
            Map.entry("true", TokenType.BOOLEAN_LITERAL),
            Map.entry("false", TokenType.BOOLEAN_LITERAL)));

    private static final Map<Character, TokenType> SYMBOLS = Objects.requireNonNull(Map.ofEntries(
            Map.entry('\0', TokenType.EOF),
            Map.entry('=', TokenType.EQUAL),
            Map.entry('+', TokenType.PLUS),
            Map.entry('-', TokenType.MINUS),
            Map.entry('!', TokenType.BANG),
            Map.entry('<', TokenType.LESS),
            Map.entry('>', TokenType.GREATER),
            Map.entry('*', TokenType.STAR),
            Map.entry('/', TokenType.SLASH),
            Map.entry('%', TokenType.PERCENT),
            Map.entry(':', TokenType.COLON),
            Map.entry(';', TokenType.SEMICOLON),
            Map.entry(',', TokenType.COMMA),
            Map.entry('(', TokenType.LEFT_PAREN),
            Map.entry(')', TokenType.RIGHT_PAREN),
            Map.entry('{', TokenType.LEFT_BRACE),
            Map.entry('}', TokenType.RIGHT_BRACE),
            Map.entry('[', TokenType.LEFT_BRACKET),
            Map.entry(']', TokenType.RIGHT_BRACKET)));

    private static final Map<String, TokenType> TWO_CHARACTER_SYMBOLS = Objects.requireNonNull(Map.ofEntries(
            Map.entry("==", TokenType.EQUAL_EQUAL),
            Map.entry("=>", TokenType.FAT_ARROW),
            Map.entry("++", TokenType.PLUS_PLUS),
            Map.entry("--", TokenType.MINUS_MINUS),
            Map.entry("!=", TokenType.BANG_EQUAL),
            Map.entry("<=", TokenType.LESS_EQUAL),
            Map.entry(">=", TokenType.GREATER_EQUAL),
            Map.entry("&&", TokenType.AND),
            Map.entry("||", TokenType.OR)));

    private final String source;
    private int position, line, column;
    private Position tokenStart = new Position(1, 1);

    public Lexer(final String source) {
        this.source = source;
        this.position = 0;
        this.line = 1;
        this.column = 1;
    }

    public List<@NonNull Token> getTokens() {
        final List<@NonNull Token> tokens = new ArrayList<>();

        while (true) {
            final Token token = nextToken();
            if (token == null) {
                break;
            }
            tokens.add(token);
        }

        return tokens;
    }

    public @Nullable Token nextToken() {
        skipWhitespace();

        final Character next = peek();

        if (next == null) {
            return null;
        }

        tokenStart = new Position(line, column);

        if (Character.isDigit(next)) {
            return readNumberLiteral();
        }

        if (Character.isAlphabetic(next) || next == '_') {
            return readIdentifier();
        }

        if (next == '\'') {
            return readCharLiteral();
        }

        if (next == '"') {
            return readStringLiteral();
        }

        if (position + 1 < source.length()) {
            final String text = Objects.requireNonNull(source.substring(position, position + 2));
            final TokenType type = TWO_CHARACTER_SYMBOLS.get(text);
            if (type != null) {
                advance();
                advance();
                return createToken(type, text);
            }
        }

        final TokenType symbolType = SYMBOLS.get(next);

        if (symbolType != null) {
            advance();
            return createToken(symbolType, next);
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
        }
    }

    private Token readIdentifier() {
        final StringBuilder builder = new StringBuilder();
        while (true) {
            final Character next = peek();
            if (next == null || !(next == '_' || Character.isAlphabetic(next) || Character.isDigit(next))) {
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
                int end = position + 1;
                while (end < source.length() && source.charAt(end) == '_') {
                    end++;
                }
                if (end >= source.length() || !Character.isDigit(source.charAt(end))) {
                    return;
                }
                while (position < end) {
                    builder.append('_');
                    advance();
                }
                continue;
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
            if (next == '\\') {
                final Character escaped = peek();
                if (escaped != null && (escaped == '"' || escaped == '\\')) {
                    // Keep the source spelling while consuming the escaped character.
                    builder.append(escaped);
                    advance();
                }
            }
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
        final Position end = new Position(line, column);
        final Range range = new Range(tokenStart, end);
        return new Token(type, text, range);
    }

    private Token createToken(final TokenType type, final char c) {
        return createToken(type, Objects.requireNonNull(String.valueOf(c)));
    }

    private @Nullable Character peek() {
        if (position >= source.length()) {
            return null;
        }
        return source.charAt(position);
    }

    private void advance() {
        final char consumed = source.charAt(position++);
        if (consumed == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }

}
