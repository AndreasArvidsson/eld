package mylang.parser;

import org.jspecify.annotations.Nullable;
import mylang.lexer.Range;

public record ForStatement(
        @Nullable Statement initializer,
        @Nullable Expression condition,
        @Nullable Expression update,
        BlockStatement body,
        Range range) implements Statement {
}
