package mylang.parser;

import mylang.Range;

public record GroupingExpression(
        Expression expression,
        Range range) implements Expression {
}
