package com.github.andreasarvidsson.eld.parser;

public sealed interface SwitchBranchBody extends AstNode
    permits SwitchBranchExpressionBody, SwitchBranchBlockBody {

}
