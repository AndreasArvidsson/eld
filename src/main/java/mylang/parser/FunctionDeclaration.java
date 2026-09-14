package mylang.parser;

import java.util.List;
import org.jspecify.annotations.Nullable;
import mylang.lexer.Range;

public record FunctionDeclaration(
        String name,
        List<Parameter> parameters,
        @Nullable TypeNode returnType,
        BlockStatement body,
        Range range) implements Declaration {
}
