package com.github.andreasarvidsson.eld.parser;

public sealed interface Expression extends AstNode
    permits TernaryExpression, SwitchExpression, IfExpression,
    IdentifierExpression, LiteralExpression, UnaryExpression, PostfixExpression,
    BinaryExpression, AssignmentExpression, CallExpression, SubscriptExpression,
    SliceExpression, ArrayExpression, GroupingExpression, LambdaExpression {
}
