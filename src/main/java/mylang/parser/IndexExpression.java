package mylang.parser;

import mylang.lexer.Range;

public record IndexExpression(
        Expression target,
        Expression index,
        Range range) implements Expression {
}
