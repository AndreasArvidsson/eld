package mylang.parser;

public sealed interface Declaration extends BlockItem
    permits VariableDeclaration, ClassDeclaration, FunctionDeclaration,
    IdentifierDeclaration {
}
