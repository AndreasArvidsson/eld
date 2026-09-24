package com.github.andreasarvidsson.eld.parser;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Position;
import com.github.andreasarvidsson.eld.lexer.Token;
import com.github.andreasarvidsson.eld.lexer.TokenType;

public class ParserBase {
    private final TokenStream tokens;

    protected ParserBase(final TokenStream tokens) {
        this.tokens = tokens;
    }

    protected Token current() {
        return Objects.requireNonNull(tokens.current());
    }

    protected Token advance() {
        return Objects.requireNonNull(tokens.advance());
    }

    protected void goBack() {
        tokens.goBack();
    }

    protected boolean isAtEnd() {
        return tokens.isAtEnd();
    }

    protected boolean isAtEnd(final int offset) {
        return tokens.isAtEnd(offset);
    }

    protected Token peek(final int offset) {
        return Objects.requireNonNull(tokens.peek(offset));
    }

    protected boolean check(final TokenType type) {
        return !isAtEnd() && current().type() == type;
    }

    protected boolean check(final int offset, final TokenType type) {
        final Token token = tokens.peek(offset);
        return token != null && token.type() == type;
    }

    protected boolean match(final TokenType type) {
        return matchToken(type) != null;
    }

    protected @Nullable Token matchToken(final TokenType type) {
        if (isAtEnd() || current().type() != type) {
            return null;
        }

        return advance();
    }

    protected Token expect(final TokenType type) {
        if (isAtEnd()) {
            throw ParserException
                .expectedEof(getLastPosition(), type.toString());
        }

        final Token token = current();

        if (token.type() != type) {
            throw ParserException.expected(type, token);
        }

        advance();

        return token;
    }

    protected Position getLastPosition() {
        final @Nullable Token last = tokens.last();
        return last != null ? last.range().end() : new Position(0, 0);
    }
}
