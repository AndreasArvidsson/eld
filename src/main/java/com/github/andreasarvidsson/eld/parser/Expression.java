package com.github.andreasarvidsson.eld.parser;

public sealed interface Expression extends AstNode
    permits MemberExpression, NewExpression, TernaryExpression,
    SwitchExpression, IfExpression, IdentifierExpression, LiteralExpression,
    UnaryExpression, PostfixExpression, BinaryExpression, AssignmentExpression,
    CallExpression, SubscriptExpression, SliceExpression, ArrayExpression,
    TupleExpression, GroupingExpression, LambdaExpression,
    NamedArgumentExpression, ThisExpression, FormatStringExpression,
    ObjectExpression, MapExpression, ArraySpread, AwaitExpression {
}
