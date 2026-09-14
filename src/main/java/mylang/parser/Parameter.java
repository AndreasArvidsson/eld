package mylang.parser;

import mylang.Range;
import org.jspecify.annotations.Nullable;

public record Parameter(
        String name,
        @Nullable TypeNode type,
        Range range) implements AstNode {
}
