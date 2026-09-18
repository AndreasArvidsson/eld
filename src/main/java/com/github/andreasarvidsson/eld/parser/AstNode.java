package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public sealed interface AstNode
    permits BlockItem, Expression, TypeNode, FunctionParameter, LambdaParameter,
    Program, ElseIfBranch, SwitchBranch, SwitchElseBranch, SwitchBranchBody {

    Range range();

    default String toAstString() {
        return AstPrinter.print(this);
    }
}
