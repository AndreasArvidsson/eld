package mylang.parser;

public sealed interface Expression extends AstNode permits TernaryExpression,
    IfExpression, IdentifierExpression, LiteralExpression, UnaryExpression,
    PostfixExpression, BinaryExpression, AssignmentExpression, CallExpression,
    IndexExpression, ArrayExpression, GroupingExpression, LambdaExpression {
}
