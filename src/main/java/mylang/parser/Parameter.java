package mylang.parser;

import mylang.Range;

public record Parameter(
        String name,
        TypeNode type,
        Range range) implements AstNode {
}
