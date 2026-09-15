package mylang.parser;

import mylang.Range;

public record ElseIfBranch(
    Expression condition, BlockStatement branch, Range range
) implements AstNode {
}
