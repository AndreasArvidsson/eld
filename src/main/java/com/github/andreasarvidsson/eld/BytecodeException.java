package com.github.andreasarvidsson.eld;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

public final class BytecodeException extends RuntimeException {
    @FormatMethod
    public BytecodeException(
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(Objects.requireNonNull(String.format(format, args)));
    }
}
