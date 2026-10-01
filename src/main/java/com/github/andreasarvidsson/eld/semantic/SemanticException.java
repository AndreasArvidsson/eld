package com.github.andreasarvidsson.eld.semantic;

import org.jspecify.annotations.Nullable;
import java.util.List;
import com.github.andreasarvidsson.eld.BaseException;
import com.github.andreasarvidsson.eld.Range;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

public final class SemanticException extends BaseException {
    private final List<String> availableOverloads;

    @FormatMethod
    public SemanticException(
        final Range range,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        this(range, List.of(), format, args);
    }

    @FormatMethod
    public SemanticException(
        final Range range,
        final List<String> availableOverloads,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(range, format.formatted(args));
        this.availableOverloads = List.copyOf(availableOverloads);
    }

    @Override
    public String getMessage() {
        return availableOverloads.isEmpty()
            ? super.getMessage()
            : super.getMessage() + "\nAvailable overloads:\n  "
                + String.join("\n  ", availableOverloads);
    }
}
