package com.github.andreasarvidsson.eld.semantic;

import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.BaseException;
import com.github.andreasarvidsson.eld.Range;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

public final class SemanticException extends BaseException {
    @FormatMethod
    public SemanticException(
        final Range range,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(range, format.formatted(args));
    }
}
