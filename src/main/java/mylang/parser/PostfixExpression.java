package mylang.parser;

import mylang.Range;

public record PostfixExpression(
    Expression operand, PostfixOperator operator, Range range
) implements Expression {
}
