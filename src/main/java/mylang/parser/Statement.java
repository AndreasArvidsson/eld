package mylang.parser;

public sealed interface Statement extends BlockItem
        permits BlockStatement,
        DeclarationStatement,
        ExpressionStatement,
        ReturnStatement,
        IfStatement,
        WhileStatement,
        DoWhileStatement,
        ForStatement,
        ForEachStatement,
        BreakStatement,
        ContinueStatement {
}
