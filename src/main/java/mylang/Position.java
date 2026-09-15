package mylang;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

public record Position(int line, int column) implements Comparable<@NonNull Position> {

    @Override
    public int compareTo(final Position other) {
        if (this.line() != other.line()) {
            return this.line() - other.line();
        }
        return this.column() - other.column();
    }

    @Override
    public String toString() {
        return Objects.requireNonNull(String.format("%d:%d", line(), column()));
    }
}
