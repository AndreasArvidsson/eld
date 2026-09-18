package com.github.andreasarvidsson.eld.parser;

public sealed interface ObjectEntry extends AstNode
    permits ObjectMember, ObjectSpread {
    Expression value();
}
