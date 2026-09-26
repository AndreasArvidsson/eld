package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record MemberDeclaration(
    Visibility visibility, boolean staticMember, Declaration declaration,
    Range range
) implements AstNode {
}
