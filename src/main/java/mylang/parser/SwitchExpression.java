package mylang.parser;

import java.util.List;
import org.jspecify.annotations.Nullable;
import mylang.Range;

public record SwitchExpression(
    Expression subject, List<SwitchBranch> branches,
    @Nullable SwitchElseBranch elseBranch, Range range
) implements Expression {
}
