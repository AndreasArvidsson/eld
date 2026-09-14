package mylang.parser;

import java.util.List;
import org.jspecify.annotations.Nullable;

import mylang.Range;

public record FunctionTypeNode(
        List<TypeNode> parameterTypes,
        @Nullable TypeNode returnType,
        Range range) implements TypeNode {
}
