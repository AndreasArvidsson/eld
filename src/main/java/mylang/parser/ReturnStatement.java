package mylang.parser;

import org.jspecify.annotations.Nullable;
import mylang.lexer.Range;

public record ReturnStatement(
        @Nullable Expression value,
        Range range) implements Statement {
}
