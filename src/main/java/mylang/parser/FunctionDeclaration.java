package mylang.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import mylang.Range;

public record FunctionDeclaration(
        IdentifierDeclaration name,
        List<@NonNull Parameter> parameters,
        @Nullable TypeNode returnType,
        BlockStatement body,
        Range range) implements Declaration {
}
