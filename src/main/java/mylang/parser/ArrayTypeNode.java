package mylang.parser;

import mylang.lexer.Range;

public record ArrayTypeNode(
        TypeNode elementType,
        Range range) implements TypeNode {
}
