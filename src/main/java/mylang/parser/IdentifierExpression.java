package mylang.parser;

import mylang.lexer.Range;

public record IdentifierExpression(
        String name,
        Range range) implements Expression {
}
