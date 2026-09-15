package mylang;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public abstract class BaseException extends RuntimeException {

    private final @Nullable Range range;

    public BaseException(final Range range, final String message) {
        super(message);
        this.range = range;
    }

    public BaseException(final String message) {
        super(message);
        this.range = null;
    }

    @Override
    public String getMessage() {
        if (range == null) {
            return Objects.requireNonNull(super.getMessage());
        }
        return super.getMessage() + " at " + range;
    }
}
