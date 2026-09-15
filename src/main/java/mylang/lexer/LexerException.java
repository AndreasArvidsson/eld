package mylang.lexer;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;

import mylang.BaseException;
import mylang.Range;

public final class LexerException extends BaseException {
    @FormatMethod
    public LexerException(
        final Range range,
        @FormatString final String format,
        final @Nullable Object... args
    ) {
        super(range, Objects.requireNonNull(String.format(format, args)));
    }
}
