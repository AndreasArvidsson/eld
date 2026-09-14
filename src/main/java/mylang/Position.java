package mylang;

import java.util.Objects;

public record Position(int line, int column) {
    @Override
    public String toString() {
        return Objects.requireNonNull(String.format("%d:%d", line(), column()));
    }
}
