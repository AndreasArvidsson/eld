package mylang.parser;

import mylang.Range;

public record NamedTypeNode(String name, Range range) implements TypeNode {
}
