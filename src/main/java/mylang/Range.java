package mylang;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

public record Range(Position start, Position end)
    implements Comparable<@NonNull Range> {
    public Range(
        final int startLine,
        final int startColumn,
        final int endLine,
        final int endColumn
    ) {
        this(
            new Position(startLine, startColumn),
            new Position(endLine, endColumn)
        );
    }

    @Override
    public String toString() {
        return Objects.requireNonNull(String.format("%s-%s", start(), end()));
    }

    @Override
    public int compareTo(final Range other) {
        final int cmp = this.start().compareTo(other.start());
        if (cmp != 0) {
            return cmp;
        }
        return this.end().compareTo(other.end());
    }
}
