package mylang.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import mylang.Range;

public record LambdaExpression(
        List<@NonNull Parameter> parameters,
        @Nullable TypeNode returnType,
        AstNode body,
        Range range) implements Expression {
}
