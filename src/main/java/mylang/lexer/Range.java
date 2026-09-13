package mylang.lexer;

import java.util.Objects;

public record Range(Position start, Position end) {
    public Range(final int startLine, final int startColumn, final int endLine, final int endColumn) {
        this(new Position(startLine, startColumn), new Position(endLine, endColumn));
    }

    @Override
    public String toString() {
        return Objects.requireNonNull(String.format("%s-%s", start(), end()));
    }
}
