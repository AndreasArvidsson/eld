package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public sealed interface AstNode
    permits BlockItem, Expression, TypeNode, FunctionParameter, LambdaParameter,
    Program, ElseIfBranch, SwitchBranch, SwitchElseBranch, SwitchBranchBody,
    MemberDeclaration, InterfaceMemberDeclaration, ObjectEntry {

    Range range();

    default String toAstString() {
        return AstPrinter.print(this);
    }
}
