package mylang.parser;

import mylang.lexer.Range;

public record Parameter(
        String name,
        TypeNode type,
        Range range) implements AstNode {
}
