package mylang.parser;

import mylang.Range;

public record SwitchBranchExpressionBody(Expression expression, Range range)
    implements SwitchBranchBody {
}
