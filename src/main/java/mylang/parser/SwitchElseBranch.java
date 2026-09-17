package mylang.parser;

import mylang.Range;

public record SwitchElseBranch(SwitchBranchBody body, Range range)
    implements AstNode {
}
