package mylang.parser;

import org.jspecify.annotations.Nullable;

import mylang.Range;

public record VariableDeclaration(
        Mutability mutability,
        Identifier name,
        @Nullable TypeNode type,
        @Nullable Expression initializer,
        Range range) implements Declaration {
}
