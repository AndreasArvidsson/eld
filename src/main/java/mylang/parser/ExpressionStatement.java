package mylang.parser;

import mylang.Range;

public record ExpressionStatement(Expression expression, Range range)
    implements Statement {
}
