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
                Map.entry("abstract", TokenType.ABSTRACT),
                Map.entry("enum", TokenType.ENUM),
                Map.entry("record", TokenType.RECORD),
                Map.entry("extends", TokenType.EXTENDS),
                Map.entry("interface", TokenType.INTERFACE),
                Map.entry("implements", TokenType.IMPLEMENTS),
                Map.entry("permits", TokenType.PERMITS),
                Map.entry("as", TokenType.AS),
                Map.entry("instanceof", TokenType.INSTANCEOF),
                Map.entry("new", TokenType.NEW),
                Map.entry("constructor", TokenType.CONSTRUCTOR),
                Map.entry("this", TokenType.THIS),
                Map.entry("super", TokenType.SUPER),
                Map.entry("func", TokenType.FUNC),
                Map.entry("final", TokenType.FINAL),
                Map.entry("override", TokenType.OVERRIDE),
                Map.entry("async", TokenType.ASYNC),
                Map.entry("static", TokenType.STATIC),
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
                Map.entry('&', TokenType.BIT_AND),
                Map.entry('^', TokenType.BIT_XOR),
                Map.entry('~', TokenType.TILDE),
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
                Map.entry("+=", TokenType.PLUS_EQUAL),
                Map.entry("-=", TokenType.MINUS_EQUAL),
                Map.entry("*=", TokenType.STAR_EQUAL),
                Map.entry("/=", TokenType.SLASH_EQUAL),
                Map.entry("%=", TokenType.PERCENT_EQUAL),
                Map.entry("!=", TokenType.BANG_EQUAL),
                Map.entry("&&", TokenType.AND),
                Map.entry("||", TokenType.OR),
                Map.entry("&=", TokenType.BIT_AND_EQUAL),
                Map.entry("|=", TokenType.BIT_OR_EQUAL),
                Map.entry("^=", TokenType.BIT_XOR_EQUAL)
            )
        );

    private static final class FormatContext {
        private boolean text = true;
        private int braces;
        private final int openingLine;
        private final int openingColumn;
        private final int openingPosition;
        private final boolean multiline;
        private final int closingPosition;
        private final String indentation;

        private FormatContext(
            final int openingLine,
            final int openingColumn,
            final int openingPosition,
            final boolean multiline,
            final int closingPosition,
            final String indentation
        ) {
            this.openingLine = openingLine;
            this.openingColumn = openingColumn;
            this.openingPosition = openingPosition;
            this.multiline = multiline;
            this.closingPosition = closingPosition;
            this.indentation = indentation;
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
            if (!context.multiline && line > context.openingLine) {
                int newline = source.indexOf('\n', context.openingPosition);
                if (
                    newline > context.openingPosition
                        && source.charAt(newline - 1) == '\r'
                ) {
                    newline--;
                }
                final int newlineColumn =
                    context.openingColumn + newline - context.openingPosition;
                throw new LexerException(
                    new Range(
                        context.openingLine,
                        newlineColumn,
                        context.openingLine,
                        newlineColumn + 1
                    ),
                    "Unterminated string literal"
                );
            }
            if (token == null) {
                throw new LexerException(
                    new Range(line, column, line, column + 1),
                    "Unterminated string literal"
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
        if (context.multiline) {
            return readMultilineFormatText(context);
        }
        final StringBuilder builder = new StringBuilder();
        while (true) {
            final Character next = peek();
            if (next == null) {
                throw new LexerException(
                    new Range(line, column, line, column + 1),
                    "Unterminated string literal"
                );
            }
            if (next == '\n' || next == '\r') {
                throw new LexerException(
                    new Range(line, column, line, column + 1),
                    "Unterminated string literal"
                );
            }
            if (next == '"' || (next == '{' && !Objects.equals(peek(1), '{'))) {
                if (!builder.isEmpty()) {
                    return createToken(
                        TokenType.FORMAT_STRING_TEXT,
                        builder.toString()
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

    private Token readMultilineFormatText(final FormatContext context) {
        final StringBuilder builder = new StringBuilder();
        if (position == context.openingPosition) {
            if (peek() != null && peek() == '\r') {
                advance();
            }
            advance();
            tokenStart = new Position(line, column);
        }
        Position contentEnd = new Position(line, column);
        final int closingLineStart =
            source.lastIndexOf('\n', context.closingPosition - 1) + 1;
        while (true) {
            if (position == context.closingPosition) {
                if (!builder.isEmpty()) {
                    return createMultilineFormatTextToken(builder, contentEnd);
                }
                tokenStart = new Position(line, column);
                advance();
                advance();
                advance();
                formats.pop();
                return createToken(TokenType.FORMAT_STRING_END, "\"\"\"");
            }
            if (position == closingLineStart) {
                for (int i = 0; i < context.indentation.length(); i++) {
                    advance();
                }
                if (builder.isEmpty()) {
                    tokenStart = new Position(line, column);
                }
                continue;
            }
            if (position > 0 && source.charAt(position - 1) == '\n') {
                int lineEnd = source.indexOf('\n', position);
                if (lineEnd < 0) {
                    lineEnd = source.length();
                }
                if (lineEnd > position && source.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                final String row = source.substring(position, lineEnd);
                final int skip =
                    row.isBlank() ? row.length() : context.indentation.length();
                for (int i = 0; i < skip; i++) {
                    advance();
                }
                if (builder.isEmpty()) {
                    tokenStart = new Position(line, column);
                }
            }
            final char next = source.charAt(position);
            if (next == '\r' || next == '\n') {
                if (next == '\r') {
                    advance();
                }
                advance();
                if (position != closingLineStart) {
                    builder.append('\n');
                    contentEnd = new Position(line, column);
                }
                continue;
            }
            if (next == '{' && !Objects.equals(peek(1), '{')) {
                if (!builder.isEmpty()) {
                    return createMultilineFormatTextToken(builder, contentEnd);
                }
                tokenStart = new Position(line, column);
                advance();
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
            contentEnd = new Position(line, column);
        }
    }

    private Token createMultilineFormatTextToken(
        final StringBuilder builder,
        final Position end
    ) {
        return new Token(
            TokenType.FORMAT_STRING_TEXT,
            builder.toString(),
            new Range(tokenStart, end)
        );
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
                final boolean multiline = source.startsWith("\"\"\"", position);
                if (formatted) {
                    int closingPosition = -1;
                    String indentation = "";
                    if (multiline) {
                        final int openingPosition = position;
                        final int openingLine = line;
                        final int openingColumn = column;
                        readMultilineStringLiteral(false, true);
                        closingPosition = position - 3;
                        final int closingLineStart =
                            source.lastIndexOf('\n', closingPosition - 1) + 1;
                        indentation =
                            source.substring(closingLineStart, closingPosition);
                        position = openingPosition;
                        line = openingLine;
                        column = openingColumn;
                        advance();
                        advance();
                        advance();
                    }
                    else {
                        advance();
                    }
                    formats.push(
                        new FormatContext(
                            line,
                            column,
                            position,
                            multiline,
                            closingPosition,
                            indentation
                        )
                    );
                    return createToken(
                        TokenType.FORMAT_STRING_START,
                        prefix + (multiline ? "\"\"\"" : "\"")
                    );
                }
                if (multiline) {
                    return readMultilineStringLiteral(raw, false);
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
            if (source.startsWith("\"\"\"", position)) {
                return readMultilineStringLiteral(false, false);
            }
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
        final Character prefix = peek(1);
        if (
            Objects.equals(peek(), '0')
                && (Objects.equals(prefix, 'x') || Objects.equals(prefix, 'X')
                    || Objects.equals(prefix, 'b')
                    || Objects.equals(prefix, 'B'))
        ) {
            final int radix =
                Objects.equals(prefix, 'x') || Objects.equals(prefix, 'X')
                    ? 16
                    : 2;
            builder.append('0');
            advance();
            builder.append(source.charAt(position));
            advance();
            if (!isRadixDigit(peek(), radix)) {
                throw invalidNumericLiteral();
            }
            readDigits(builder, radix);
            final Character suffix = peek();
            final Character afterDot = peek(1);
            if (
                suffix != null && suffix == '.'
                    && (Objects.equals(afterDot, '_')
                        || (afterDot != null && Character.isDigit(afterDot)))
            ) {
                advance();
                throw invalidNumericLiteral();
            }
            if (
                suffix != null
                    && (suffix == '_' || Character.isLetterOrDigit(suffix))
            ) {
                throw invalidNumericLiteral();
            }
            return createToken(TokenType.INTEGER_LITERAL, builder.toString());
        }
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
        readDigits(builder, 10);
    }

    private void readDigits(final StringBuilder builder, final int radix) {
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
                        || !isRadixDigit(source.charAt(end), radix)
                ) {
                    throw invalidNumericLiteral();
                }
                continue;
            }
            else if (!isRadixDigit(next, radix)) {
                return;
            }
            builder.append(next);
            advance();
        }
    }

    private static boolean isRadixDigit(
        final @Nullable Character digit,
        final int radix
    ) {
        return digit != null && Character.digit(digit, radix) >= 0;
    }

    private Token readStringLiteral(final boolean raw) {
        // Skip the opening double quote.
        advance();

        final StringBuilder builder = new StringBuilder();
        while (true) {
            final Character next = peek();
            if (next == null || next == '"' || next == '\n' || next == '\r') {
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

    private Token readMultilineStringLiteral(
        final boolean raw,
        final boolean formatted
    ) {
        final int start = position;
        advance();
        advance();
        advance();
        if (peek() != null && peek() == '\r') {
            advance();
        }
        if (peek() == null || peek() != '\n') {
            throw new LexerException(
                new Range(line, column, line, column + 1),
                "Multiline string opening delimiter must be followed by a newline"
            );
        }
        advance();
        final int contentStart = position;
        int lineStart = position;
        while (peek() != null) {
            if (source.startsWith("\"\"\"", position)) {
                final String indentation =
                    source.substring(lineStart, position);
                if (!indentation.isBlank()) {
                    throw new LexerException(
                        new Range(line, column, line, column + 3),
                        "Multiline string closing delimiter must be on its own line"
                    );
                }
                validateMultilineIndentation(
                    contentStart,
                    lineStart,
                    indentation
                );
                advance();
                advance();
                advance();
                return createToken(
                    raw
                        ? TokenType.RAW_STRING_LITERAL
                        : TokenType.STRING_LITERAL,
                    Objects.requireNonNull(source.substring(start, position))
                );
            }
            final char current = source.charAt(position);
            if (formatted && current == '{') {
                if (Objects.equals(peek(1), '{')) {
                    advance();
                    advance();
                }
                else {
                    skipMultilineInterpolation();
                    lineStart = source.lastIndexOf('\n', position - 1) + 1;
                }
                continue;
            }
            if (
                current == '\\' && position + 1 < source.length()
                    && (source.charAt(position + 1) == '"'
                        || source.charAt(position + 1) == '\\')
            ) {
                advance();
                advance();
                continue;
            }
            advance();
            if (current == '\n') {
                lineStart = position;
            }
        }
        throw new LexerException(
            new Range(line, column, line, column + 1),
            "Unterminated multiline string literal"
        );
    }

    private void skipMultilineInterpolation() {
        int braces = 0;
        while (peek() != null) {
            final char current = source.charAt(position);
            if (current == '"' || current == '\'') {
                skipInterpolationQuotedLiteral(current);
                continue;
            }
            if (current == '/' && Objects.equals(peek(1), '/')) {
                skipLineComment();
                continue;
            }
            if (current == '/' && Objects.equals(peek(1), '*')) {
                skipBlockComment();
                continue;
            }
            advance();
            if (current == '{') {
                braces++;
            }
            else if (current == '}' && --braces == 0) {
                return;
            }
        }
        throw new LexerException(
            new Range(line, column, line, column + 1),
            "Unterminated multiline string literal"
        );
    }

    private void skipInterpolationQuotedLiteral(final char quote) {
        final boolean triple =
            quote == '"' && source.startsWith("\"\"\"", position);
        final int delimiterLength = triple ? 3 : 1;
        for (int i = 0; i < delimiterLength; i++) {
            advance();
        }
        while (peek() != null) {
            if (source.charAt(position) == '\\' && peek(1) != null) {
                advance();
                advance();
                continue;
            }
            if (triple && source.startsWith("\"\"\"", position)) {
                advance();
                advance();
                advance();
                return;
            }
            if (!triple && source.charAt(position) == quote) {
                advance();
                return;
            }
            advance();
        }
    }

    private void validateMultilineIndentation(
        final int contentStart,
        final int closingLineStart,
        final String indentation
    ) {
        final String content = source.substring(contentStart, closingLineStart);
        int contentLine = tokenStart.line() + 1;
        for (final String row : content.split("\\r?\\n", -1)) {
            if (!row.isBlank() && !row.startsWith(indentation)) {
                throw new LexerException(
                    new Range(contentLine, 1, contentLine, row.length() + 1),
                    "Multiline string content has insufficient indentation"
                );
            }
            contentLine++;
        }
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
