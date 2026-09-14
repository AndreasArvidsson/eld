package mylang.parser;

import mylang.lexer.Range;

public record ExpressionStatement(
        Expression expression,
        Range range) implements Statement {
}
