package mylang.parser;

import mylang.Range;

public sealed interface AstNode
        permits Declaration, Statement, Expression, TypeNode, Parameter, Program {

    Range range();

    default String toAstString() {
        return AstPrinter.print(this);
    }
}
