package mylang.parser;

import mylang.Range;

public record DoWhileStatement(
    BlockStatement body, Expression condition, Range range
) implements Statement {
}
