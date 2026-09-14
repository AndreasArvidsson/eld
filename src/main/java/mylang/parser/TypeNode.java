package mylang.parser;

public sealed interface TypeNode extends AstNode
        permits NamedTypeNode, ArrayTypeNode, FunctionTypeNode {
}
