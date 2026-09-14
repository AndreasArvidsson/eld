package mylang.parser;

import mylang.Range;

public record BinaryExpression(
        Expression left,
        BinaryOperator operator,
        Expression right,
        Range range) implements Expression {
}
