package mylang.parser;

import mylang.Range;
import org.jspecify.annotations.Nullable;

public record SliceExpression(
    Expression target, @Nullable Expression startIndex,
    @Nullable Expression endIndex, Range range
) implements Expression {
}
