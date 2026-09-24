package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.BaseException;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.lexer.Token;
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

    @FormatMethod
    public ParserException(
        final Range range,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(range, format.formatted(args));
    }
}
