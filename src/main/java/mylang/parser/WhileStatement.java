package mylang.parser;

import mylang.Range;

public record WhileStatement(
        Expression condition,
        BlockStatement body,
        Range range) implements Statement {
}
