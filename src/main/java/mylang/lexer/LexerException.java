package mylang.lexer;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class LexerException extends RuntimeException {

    private final Range range;

    public LexerException(final Range range, final String message) {
        super(message);
        this.range = range;
    }

    @FormatMethod
    public LexerException(final Range range, @FormatString final String format, final @Nullable Object... args) {
        this(range, Objects.requireNonNull(String.format(format, args)));
    }

    public Range range() {
        return range;
    }

    @Override
    public String getMessage() {
        return super.getMessage() + " at " + range();
    }
}
