package com.github.andreasarvidsson.eld.parser;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import com.github.andreasarvidsson.eld.BaseException;
import com.github.andreasarvidsson.eld.Range;

public class ParserException extends BaseException {
    @FormatMethod
    public ParserException(
        final Range range,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(range, format.formatted(args));
    }
}
