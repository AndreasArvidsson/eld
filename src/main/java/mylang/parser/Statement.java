package mylang.parser;

public sealed interface Statement extends BlockItem
    permits YieldStatement, BlockStatement, DeclarationStatement,
    ExpressionStatement, ReturnStatement, WhileStatement, DoWhileStatement,
    ForStatement, ForEachStatement, BreakStatement, ContinueStatement {
}
