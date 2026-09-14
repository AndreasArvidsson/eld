package mylang.parser;

import org.jspecify.annotations.Nullable;

import mylang.Range;

public record IfStatement(
        Expression condition,
        BlockStatement thenBranch,
        @Nullable Statement elseBranch,
        Range range) implements Statement {
}
