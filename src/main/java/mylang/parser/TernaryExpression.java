package mylang.parser;

import mylang.Range;

public record TernaryExpression(
    Expression condition, Expression thenBranch, Expression elseBranch,
    Range range
) implements Expression {
}
