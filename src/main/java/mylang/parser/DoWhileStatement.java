package mylang.parser;

import mylang.lexer.Range;

public record DoWhileStatement(
        BlockStatement body,
        Expression condition,
        Range range) implements Statement {
}
