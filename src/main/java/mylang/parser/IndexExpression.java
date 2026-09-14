package mylang.parser;

import mylang.Range;

public record IndexExpression(
        Expression target,
        Expression index,
        Range range) implements Expression {
}
