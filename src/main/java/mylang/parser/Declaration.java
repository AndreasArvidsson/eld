package mylang.parser;

public sealed interface Declaration extends AstNode
        permits VariableDeclaration, FunctionDeclaration {
}
