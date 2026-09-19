package com.github.andreasarvidsson.eld.parser;

public sealed interface Statement extends BlockItem
    permits YieldStatement, BlockStatement, DeclarationStatement,
    ExpressionStatement, ReturnStatement, WhileStatement, DoWhileStatement,
    ForStatement, ForEachStatement, BreakStatement, ContinueStatement,
    SuperConstructorCall, TryStatement, ThrowStatement {
}
