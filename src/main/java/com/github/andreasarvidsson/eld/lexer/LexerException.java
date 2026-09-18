package com.github.andreasarvidsson.eld.lexer;

import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.BaseException;
import com.github.andreasarvidsson.eld.Range;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

public final class LexerException extends BaseException {
    @FormatMethod
    public LexerException(
        final Range range,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(range, format.formatted(args));
    }
}
