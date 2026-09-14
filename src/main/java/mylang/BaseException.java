package mylang;

public abstract class BaseException extends RuntimeException {

    private final Range range;

    public BaseException(final Range range, final String message) {
        super(message);
        this.range = range;
    }

    public Range range() {
        return range;
    }

    @Override
    public String getMessage() {
        return super.getMessage() + " at " + range();
    }
}
