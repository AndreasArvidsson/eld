package mylang.parser;

public sealed interface Expression extends AstNode
    permits TernaryExpression, SwitchExpression, IfExpression,
    IdentifierExpression, LiteralExpression, UnaryExpression, PostfixExpression,
    BinaryExpression, AssignmentExpression, CallExpression, IndexExpression,
    ArrayExpression, GroupingExpression, LambdaExpression {
}
