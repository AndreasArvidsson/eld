package mylang.parser;

import mylang.Range;

public record Identifier(
        String name,
        Range range) implements AstNode {
}
