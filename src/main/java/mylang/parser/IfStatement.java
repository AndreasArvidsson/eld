package mylang.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import mylang.Range;

public record IfStatement(
        Expression condition,
        BlockStatement thenBranch,
        List<@NonNull ElseIfBranch> elifBranches,
        @Nullable BlockStatement elseBranch,
        Range range) implements Statement {
}
