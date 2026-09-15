package mylang.parser;

import mylang.Range;

public record AssignmentExpression(
    Expression target, Expression value, Range range
) implements Expression {
}
