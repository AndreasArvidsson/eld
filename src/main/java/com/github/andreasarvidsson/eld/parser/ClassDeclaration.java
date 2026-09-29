package com.github.andreasarvidsson.eld.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;

public record ClassDeclaration(
    List<@NonNull ClassModifier> modifiers, IdentifierDeclaration name,
    @Nullable Extends extendsNode, @Nullable Implements implementsNode,
    @Nullable Permits permitsNode, List<@NonNull MemberDeclaration> members,
    Range range
) implements Declaration {
    public ClassDeclaration {
        final var sorted = new ArrayList<>(modifiers);
        Collections.sort(sorted);
        modifiers = Collections.unmodifiableList(sorted);
    }

    public boolean abstractClass() {
        return modifiers.contains(ClassModifier.ABSTRACT);
    }

    public boolean finalClass() {
        return modifiers.contains(ClassModifier.FINAL);
    }

    public @Nullable IdentifierExpression superClass() {
        return extendsNode == null ? null : extendsNode.superClass();
    }

    public @Nullable List<@NonNull TypeNode> implementedInterfaces() {
        return implementsNode == null ? null : implementsNode.interfaces();
    }

    public @Nullable List<@NonNull IdentifierExpression> permittedSubclasses() {
        return permitsNode == null ? null : permitsNode.subClasses();
    }

}
