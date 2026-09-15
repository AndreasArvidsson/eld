package mylang.semantic;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import mylang.BaseException;
import mylang.Range;

public final class SemanticException extends BaseException {
    @FormatMethod
    public SemanticException(
        final Range range,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(range, Objects.requireNonNull(String.format(format, args)));
    }
}
