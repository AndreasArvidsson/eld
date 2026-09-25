package com.github.andreasarvidsson.eld.lexer;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Position;
import com.github.andreasarvidsson.eld.Range;

public class Lexer {
    private static final Map<String, TokenType> KEYWORDS =
        Objects.requireNonNull(
            Map.ofEntries(
                Map.entry("const", TokenType.CONST),
                Map.entry("var", TokenType.VAR),
                Map.entry("type", TokenType.TYPE),
                Map.entry("class", TokenType.CLASS),
                Map.entry("record", TokenType.RECORD),
                Map.entry("extends", TokenType.EXTENDS),
                Map.entry("interface", TokenType.INTERFACE),
                Map.entry("implements", TokenType.IMPLEMENTS),
                Map.entry("new", TokenType.NEW),
                Map.entry("constructor", TokenType.CONSTRUCTOR),
                Map.entry("this", TokenType.THIS),
                Map.entry("super", TokenType.SUPER),
                Map.entry("func", TokenType.FUNC),
                Map.entry("final", TokenType.FINAL),
                Map.entry("async", TokenType.ASYNC),
                Map.entry("await", TokenType.AWAIT),
                Map.entry("ignore", TokenType.IGNORE),
                Map.entry("public", TokenType.PUBLIC),
                Map.entry("protected", TokenType.PROTECTED),
                Map.entry("if", TokenType.IF),
                Map.entry("switch", TokenType.SWITCH),
                Map.entry("case", TokenType.CASE),
                Map.entry("elif", TokenType.ELIF),
                Map.entry("else", TokenType.ELSE),
                Map.entry("do", TokenType.DO),
                Map.entry("while", TokenType.WHILE),
                Map.entry("for", TokenType.FOR),
                Map.entry("return", TokenType.RETURN),
                Map.entry("try", TokenType.TRY),
                Map.entry("catch", TokenType.CATCH),
                Map.entry("finally", TokenType.FINALLY),
                Map.entry("throw", TokenType.THROW),
                Map.entry("yield", TokenType.YIELD),
                Map.entry("break", TokenType.BREAK),
                Map.entry("continue", TokenType.CONTINUE),
                Map.entry("null", TokenType.NULL),
                Map.entry("true", TokenType.BOOLEAN_LITERAL),
                Map.entry("false", TokenType.BOOLEAN_LITERAL)
            )
        );

    private static final Map<Character, TokenType> SYMBOLS =
        Objects.requireNonNull(
            Map.ofEntries(
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
                Map.entry('.', TokenType.DOT),
                Map.entry('?', TokenType.QUESTION),
                Map.entry(';', TokenType.SEMICOLON),
                Map.entry(',', TokenType.COMMA),
                Map.entry('|', TokenType.PIPE),
                Map.entry('(', TokenType.LEFT_PAREN),
                Map.entry(')', TokenType.RIGHT_PAREN),
                Map.entry('{', TokenType.LEFT_BRACE),
                Map.entry('}', TokenType.RIGHT_BRACE),
                Map.entry('[', TokenType.LEFT_BRACKET),
                Map.entry(']', TokenType.RIGHT_BRACKET)
            )
        );

    private static final Map<String, TokenType> TWO_CHARACTER_SYMBOLS =
        Objects.requireNonNull(
            Map.ofEntries(
                Map.entry("==", TokenType.EQUAL_EQUAL),
                Map.entry("=>", TokenType.ARROW),
                Map.entry("++", TokenType.PLUS_PLUS),
                Map.entry("--", TokenType.MINUS_MINUS),
                Map.entry("!=", TokenType.BANG_EQUAL),
                Map.entry("<=", TokenType.LESS_EQUAL),
                Map.entry(">=", TokenType.GREATER_EQUAL),
                Map.entry("&&", TokenType.AND),
                Map.entry("||", TokenType.OR)
            )
        );

    private static final class FormatContext {
        private boolean text = true;
        private int braces;
        private final boolean raw;

        private FormatContext(final boolean raw) {
            this.raw = raw;
        }
    }

    private final Deque<FormatContext> formats = new ArrayDeque<>();
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
        final FormatContext context = formats.peek();
        if (context != null && context.text) {
            tokenStart = new Position(line, column);
            return readFormatText(context);
        }
        final Token token = readToken();
        if (context != null) {
            if (token == null) {
                throw new LexerException(
                    new Range(tokenStart, new Position(line, column)),
                    "Unterminated format string interpolation"
                );
            }
            if (token.type() == TokenType.LEFT_BRACE) {
                context.braces++;
            }
            else if (token.type() == TokenType.RIGHT_BRACE) {
                if (context.braces == 0) {
                    context.text = true;
                }
                else {
                    context.braces--;
                }
            }
        }
        return token;
    }

    private Token readFormatText(final FormatContext context) {
        final StringBuilder builder = new StringBuilder();
        while (true) {
            final Character next = peek();
            if (next == null) {
                throw new LexerException(
                    new Range(tokenStart, new Position(line, column)),
                    "Unterminated format string literal"
                );
            }
            if (next == '"' || (next == '{' && !Objects.equals(peek(1), '{'))) {
                if (!builder.isEmpty()) {
                    return createToken(
                        context.raw
                            ? TokenType.RAW_STRING_LITERAL
                            : TokenType.STRING_LITERAL,
                        Objects.requireNonNull("\"" + builder + "\"")
                    );
                }
                advance();
                if (next == '"') {
                    formats.pop();
                    return createToken(TokenType.FORMAT_STRING_END, "\"");
                }
                context.text = false;
                return createToken(TokenType.LEFT_BRACE, '{');
            }
            if (next == '{' || next == '}') {
                if (!Objects.equals(peek(1), next)) {
                    throw new LexerException(
                        new Range(line, column, line, column + 1),
                        "Unescaped closing brace in format string"
                    );
                }
                advance();
            }
            builder.append(next);
            advance();
            if (
                next == '\\' && (Objects.equals(peek(), '"')
                    || Objects.equals(peek(), '\\'))
            ) {
                builder.append(peek());
                advance();
            }
        }
    }

    private @Nullable Token readToken() {
        skipWhitespaceAndComments();

        final Character next = peek();

        if (next == null) {
            return null;
        }

        tokenStart = new Position(line, column);

        if (Character.isDigit(next)) {
            return readNumberLiteral();
        }

        if (next == 'f' || next == 'r') {
            final boolean combined =
                (next == 'r' && Objects.equals(peek(1), 'f'))
                    || (next == 'f' && Objects.equals(peek(1), 'r'));
            final int prefixLength = combined ? 2 : 1;
            if (Objects.equals(peek(prefixLength), '"')) {
                final boolean raw = next == 'r' || combined;
                final boolean formatted = next == 'f' || combined;
                final String prefix =
                    Objects.requireNonNull(
                        source.substring(position, position + prefixLength)
                    );
                for (int i = 0; i < prefixLength; i++) {
                    advance();
                }
                if (formatted) {
                    advance();
                    formats.push(new FormatContext(raw));
                    return createToken(
                        TokenType.FORMAT_STRING_START,
                        prefix + "\""
                    );
                }
                return readStringLiteral(raw);
            }
        }

        if (Character.isAlphabetic(next) || next == '_') {
            return readIdentifier();
        }

        if (next == '\'') {
            return readCharLiteral();
        }

        if (next == '"') {
            return readStringLiteral(false);
        }

        if (source.startsWith("...", position)) {
            advance();
            advance();
            advance();
            return createToken(TokenType.ELLIPSIS, "...");
        }

        if (position + 1 < source.length()) {
            final String text =
                Objects
                    .requireNonNull(source.substring(position, position + 2));
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
            "Unexpected character '%c'",
            next
        );
    }

    private void skipWhitespaceAndComments() {
        while (true) {
            final Character next = peek();
            if (next == null) {
                break;
            }
            if (Character.isWhitespace(next)) {
                advance();
                continue;
            }
            if (next == '/') {
                final Character nextNext = peek(1);
                if (nextNext != null) {
                    if (nextNext == '/') {
                        skipLineComment();
                        continue;
                    }
                    if (nextNext == '*') {
                        skipBlockComment();
                        continue;
                    }
                }
            }
            break;
        }
    }

    public void skipLineComment() {
        while (
            position < source.length() && source.charAt(position) != '\n'
                && source.charAt(position) != '\r'
        ) {
            advance();
        }
    }

    private void skipBlockComment() {
        final Position start = new Position(line, column);
        advance();
        advance();
        while (!source.startsWith("*/", position)) {
            if (position >= source.length()) {
                throw new LexerException(
                    new Range(start, new Position(line, column)),
                    "Unterminated block comment"
                );
            }
            advance();
        }
        advance();
        advance();
    }

    private Token readIdentifier() {
        final StringBuilder builder = new StringBuilder();
        while (true) {
            final Character next = peek();
            if (
                next == null || !(next == '_' || Character.isAlphabetic(next)
                    || Character.isDigit(next))
            ) {
                break;
            }
            builder.append(next);
            advance();
        }
        final String text = Objects.requireNonNull(builder.toString());
        final TokenType type =
            Objects.requireNonNull(
                KEYWORDS.getOrDefault(text, TokenType.IDENTIFIER)
            );
        return createToken(type, text);
    }

    private Token readNumberLiteral() {
        final StringBuilder builder = new StringBuilder();
        readDigits(builder);

        TokenType type = TokenType.INTEGER_LITERAL;
        final Character next = peek();

        if (
            next != null && next == '.'
                && position + 1 < source.length()
                && source.charAt(position + 1) == '_'
        ) {
            advance();
            throw invalidNumericLiteral();
        }

        if (
            next != null && next == '.'
                && position + 1 < source.length()
                && Character.isDigit(source.charAt(position + 1))
        ) {
            type = TokenType.FLOAT_LITERAL;
            builder.append(next);
            advance();

            readDigits(builder);
        }

        final Character suffix = peek();
        if (
            suffix != null && (suffix == '_' || Character.isAlphabetic(suffix))
        ) {
            throw invalidNumericLiteral();
        }

        final String text = Objects.requireNonNull(builder.toString());
        return createToken(type, text);
    }

    private LexerException invalidNumericLiteral() {
        while (true) {
            final Character next = peek();
            if (
                next == null || !(next == '_' || Character.isAlphabetic(next)
                    || Character.isDigit(next))
            ) {
                break;
            }
            advance();
        }
        return new LexerException(
            new Range(tokenStart, new Position(line, column)),
            "Invalid numeric literal"
        );
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
                while (position < end) {
                    builder.append('_');
                    advance();
                }
                if (
                    end >= source.length()
                        || !Character.isDigit(source.charAt(end))
                ) {
                    throw invalidNumericLiteral();
                }
                continue;
            }
            else if (!Character.isDigit(next)) {
                return;
            }
            builder.append(next);
            advance();
        }
    }

    private Token readStringLiteral(final boolean raw) {
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
            throw new LexerException(
                new Range(line, column, line, column + 1),
                "Unterminated string literal"
            );
        }

        // Skip the closing double quote.
        advance();

        final String text =
            Objects.requireNonNull("\"%s\"".formatted(builder.toString()));
        return createToken(
            raw ? TokenType.RAW_STRING_LITERAL : TokenType.STRING_LITERAL,
            text
        );
    }

    private Token readCharLiteral() {
        final int start = position;
        // Skip the opening single quote.
        advance();

        final Character value = peek();

        if (value == null || value == '\'') {
            throw new LexerException(
                new Range(line, column, line, column + 1),
                "Empty character literal"
            );
        }

        if (value == '\n' || value == '\r') {
            throw new LexerException(
                new Range(line, column, line, column + 1),
                "Character literal must be on a single line"
            );
        }

        advance();

        if (value == '\\') {
            final Character escaped = peek();
            if (escaped != null && (escaped == '\n' || escaped == '\r')) {
                throw new LexerException(
                    new Range(line, column, line, column + 1),
                    "Character literal must be on a single line"
                );
            }
            if (escaped == null || "btnfr0'\"\\".indexOf(escaped) < 0) {
                throw new LexerException(
                    new Range(line, column, line, column + 1),
                    "Invalid character escape"
                );
            }
            advance();
        }

        if (peek() == null || peek() != '\'') {
            throw new LexerException(
                new Range(line, column, line, column + 1),
                "Character literal must contain exactly one character"
            );
        }

        advance();

        final String text =
            Objects.requireNonNull(source.substring(start, position));
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

    private @Nullable Character peek(final int offset) {
        final int targetPosition = position + offset;
        if (targetPosition >= source.length()) {
            return null;
        }
        return source.charAt(targetPosition);
    }

    private void advance() {
        final char consumed = source.charAt(position++);
        if (consumed == '\n') {
            line++;
            column = 1;
        }
        else {
            column++;
        }
    }

}
