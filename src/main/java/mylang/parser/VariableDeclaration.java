package mylang.parser;

import org.jspecify.annotations.Nullable;
import mylang.lexer.Range;

public record VariableDeclaration(
        Mutability mutability,
        String name,
        @Nullable TypeNode type,
        Expression initializer,
        Range range) implements Declaration {
}
