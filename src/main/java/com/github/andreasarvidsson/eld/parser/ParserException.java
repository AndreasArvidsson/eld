package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.BaseException;
import com.github.andreasarvidsson.eld.Position;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.lexer.Token;
import com.github.andreasarvidsson.eld.lexer.TokenType;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

public class ParserException extends BaseException {

    public static ParserException unexpected(final Token token) {
        return new ParserException(
            token.range(),
            "Unexpected %s",
            token.type()
        );
    }

    public static ParserException expected(
        final String expected,
        final Token found
    ) {
        return new ParserException(
            found.range(),
            "Expected %s, but found %s",
            expected,
            found.type()
        );
    }

    public static ParserException expected(
        final TokenType expected,
        final Token found
    ) {
        return expected(expected.toString(), found);
    }

    public static ParserException expectedEof(
        final Position position,
        final String type
    ) {
        return new ParserException(
            new Range(position, position),
            "Expected %s, but reached end of input",
            type
        );
    }

    @FormatMethod
    public ParserException(
        final Range range,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(range, format.formatted(args));
    }
}
