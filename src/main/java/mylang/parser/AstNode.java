package mylang.parser;

import mylang.Range;

public sealed interface AstNode
        permits BlockItem,
        Expression,
        TypeNode,
        Parameter,
        Program,
        ElseIfBranch {

    Range range();

    default String toAstString() {
        return AstPrinter.print(this);
    }
}
