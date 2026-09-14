package mylang.parser;

import mylang.lexer.Range;

public record NamedTypeNode(
        String name,
        Range range) implements TypeNode {
}
