package com.github.andreasarvidsson.eld.parser;

public sealed interface InterfaceMemberDeclaration extends AstNode
    permits UninitializedVariableDeclaration, InterfaceMethodDeclaration {
}
