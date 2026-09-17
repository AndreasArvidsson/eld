package mylang.parser;

import mylang.Range;

public record SubscriptExpression(
    Expression target, Expression index, Range range
) implements Expression {
}
