package mylang.parser;

import mylang.lexer.Range;

public record UnaryExpression(
        UnaryOperator operator,
        Expression operand,
        Range range) implements Expression {
}
