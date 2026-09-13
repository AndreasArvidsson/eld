package mylang.lexer;

import java.util.Objects;

public record Position(int line, int column) {
    public String toString() {
        return Objects.requireNonNull(String.format("%d:%d", line(), column()));
    }
}
