package mylang.parser;

import mylang.lexer.Range;

public record LiteralExpression(
        LiteralKind type,
        String text,
        Range range) implements Expression {
}
