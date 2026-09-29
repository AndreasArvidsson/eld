package com.github.andreasarvidsson.eld.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record InterfaceDeclaration(
    IdentifierDeclaration name, List<@NonNull TypeNode> superInterfaces,
    @Nullable Permits permitsNode,
    List<@NonNull InterfaceMemberDeclaration> members, Range range
) implements Declaration {
    public @Nullable List<@NonNull IdentifierExpression> permittedSubclasses() {
        return permitsNode == null ? null : permitsNode.subClasses();
    }
}
