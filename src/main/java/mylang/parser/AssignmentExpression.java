package mylang.parser;

import mylang.lexer.Range;

public record AssignmentExpression(
        Expression target,
        Expression value,
        Range range) implements Expression {
}
