package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public sealed interface AstNode
    permits BlockItem, Expression, TypeNode, FunctionParameter, LambdaParameter,
    RecordParameter, Program, ElseIfBranch, SwitchBranch, SwitchElseBranch,
    SwitchBranchBody, MemberDeclaration, InterfaceMemberDeclaration,
    ObjectEntry, MapElement, CatchClause, Pattern, RecordPatternField {

    Range range();

    default String toAstString() {
        return AstPrinter.print(this);
    }
}
