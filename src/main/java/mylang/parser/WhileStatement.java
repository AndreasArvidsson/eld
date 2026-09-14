package mylang.parser;

import mylang.lexer.Range;

public record WhileStatement(
        Expression condition,
        BlockStatement body,
        Range range) implements Statement {
}
