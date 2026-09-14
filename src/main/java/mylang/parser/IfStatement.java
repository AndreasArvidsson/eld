package mylang.parser;

import org.jspecify.annotations.Nullable;
import java.util.List;

import mylang.Range;

public record IfStatement(
        Expression condition,
        BlockStatement thenBranch,
        List<ElseIfBranch> elifBranches,
        @Nullable BlockStatement elseBranch,
        Range range) implements Statement {
}
