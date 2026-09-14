package mylang.parser;

import mylang.lexer.Range;

public record GroupingExpression(
        Expression expression,
        Range range) implements Expression {
}
