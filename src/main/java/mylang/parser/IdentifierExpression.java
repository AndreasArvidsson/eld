package mylang.parser;

import mylang.Range;

public record IdentifierExpression(String name, Range range)
    implements Expression {
}
