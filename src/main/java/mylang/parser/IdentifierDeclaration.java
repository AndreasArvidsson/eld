package mylang.parser;

import mylang.Range;

public record IdentifierDeclaration(
        String name,
        Range range) implements Declaration {
}
