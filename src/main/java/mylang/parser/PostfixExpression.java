package mylang.parser;

import mylang.lexer.Range;

public record PostfixExpression(
        Expression operand,
        PostfixOperator operator,
        Range range) implements Expression {
}
