package com.github.andreasarvidsson.eld.parser;

public sealed interface BlockItem extends AstNode
    permits Declaration, Statement {
}
