package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.lexer.Token;

public class TokenStream {
    private final List<@NonNull Token> tokens;
    private int position;

    public TokenStream(final List<@NonNull Token> tokens) {
        this.tokens = tokens;
        this.position = 0;
    }

    public Token current() {
        return Objects.requireNonNull(tokens.get(position));
    }

    public @Nullable Token last() {
        return tokens.isEmpty() ? null : tokens.get(tokens.size() - 1);
    }

    public Token advance() {
        return Objects.requireNonNull(tokens.get(position++));
    }

    public void goBack() {
        position--;
    }

    public boolean isAtEnd() {
        return position >= tokens.size();
    }

    public boolean isAtEnd(final int offset) {
        return position + offset >= tokens.size();
    }

    public @Nullable Token peek(final int offset) {
        final int index = position + offset;
        if (index > -1 && index < tokens.size()) {
            return tokens.get(index);
        }
        return null;
    }
}
