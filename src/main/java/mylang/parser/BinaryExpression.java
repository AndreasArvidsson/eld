package mylang.parser;

import mylang.lexer.Range;

public record BinaryExpression(
        Expression left,
        BinaryOperator operator,
        Expression right,
        Range range) implements Expression {
}
