package mylang.parser;

import org.jspecify.annotations.Nullable;

import mylang.Range;

public record Parameter(
        IdentifierDeclaration name,
        @Nullable TypeNode type,
        Range range) implements AstNode {
}
