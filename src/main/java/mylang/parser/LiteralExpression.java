package mylang.parser;

import mylang.Range;

public record LiteralExpression(
        LiteralKind type,
        String text,
        Range range) implements Expression {
}
