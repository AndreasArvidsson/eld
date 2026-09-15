package mylang.parser;

public sealed interface BlockItem extends AstNode
    permits Declaration, Statement {
}
