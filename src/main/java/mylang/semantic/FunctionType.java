package mylang.semantic;

import java.util.List;

public record FunctionType(
        List<Type> parameterTypes,
        Type returnType) implements Type {
}
