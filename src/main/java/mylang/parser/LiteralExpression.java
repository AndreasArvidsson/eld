package mylang.parser;

import mylang.Range;

public record LiteralExpression(LiteralKind kind, String text, Range range)
    implements Expression {
}
