package mylang.lexer;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class LexerException extends RuntimeException {

    private final Range range;

    public LexerException(final Range range, final String message) {
        super(message);
        this.range = range;
    }

    public LexerException(final Range range, final String format, final @Nullable Object... args) {
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
