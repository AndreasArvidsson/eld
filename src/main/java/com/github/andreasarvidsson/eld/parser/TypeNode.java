package com.github.andreasarvidsson.eld.parser;

public sealed interface TypeNode extends AstNode
    permits NamedTypeNode, ArrayTypeNode, ConstTypeNode, FunctionTypeNode,
    UnionTypeNode, TupleTypeNode, LiteralTypeNode {
}
