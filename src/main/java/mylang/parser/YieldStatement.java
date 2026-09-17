package mylang.parser;

import mylang.Range;

public record YieldStatement(Expression value, Range range)
    implements Statement {
}
