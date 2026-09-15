package mylang.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;

import mylang.Range;

public record CallExpression(
    Expression callee, List<@NonNull Expression> arguments, Range range
) implements Expression {
}
