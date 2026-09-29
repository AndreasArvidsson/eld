package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record MemberDeclaration(
    Visibility visibility, boolean staticMember, Declaration declaration,
    Range range
) implements AstNode {

    public String getDeclarationPrefix() {
        final String visibilityText =
            visibility == Visibility.PRIVATE ? "" : visibility.value() + " ";
        final String staticText = staticMember ? "static " : "";
        return visibilityText + staticText;
    }
}
