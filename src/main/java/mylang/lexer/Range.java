package mylang.lexer;

import java.util.Objects;

public record Range(Position start, Position end) {
    public String toString() {
        return Objects.requireNonNull(String.format("%s-%s", start(), end()));
    }
}
