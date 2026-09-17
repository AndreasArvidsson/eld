package mylang.parser;

import java.util.List;
import mylang.Range;

public record SwitchBranch(
    List<Expression> matches, SwitchBranchBody body, Range range
) implements AstNode {
}
