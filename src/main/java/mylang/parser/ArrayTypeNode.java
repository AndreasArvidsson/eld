package mylang.parser;

import mylang.Range;

public record ArrayTypeNode(TypeNode elementType, Range range)
    implements TypeNode {
}
