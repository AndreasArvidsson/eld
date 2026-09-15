package mylang.parser;

import mylang.Range;

public record UnaryExpression(
    UnaryOperator operator, Expression operand, Range range
) implements Expression {
}
