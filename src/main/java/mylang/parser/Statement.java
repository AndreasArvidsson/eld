package mylang.parser;

public sealed interface Statement extends AstNode
        permits BlockStatement, DeclarationStatement, ExpressionStatement, ReturnStatement,
        IfStatement, WhileStatement, DoWhileStatement, ForStatement, ForEachStatement {
}
