package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record MemberDeclaration(
    Visibility visibility, Declaration declaration, Range range
) implements AstNode {
}
