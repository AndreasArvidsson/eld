package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;

public record RecordDeclaration(
    IdentifierDeclaration name, List<@NonNull RecordParameter> parameters,
    @Nullable Implements implementsNode,
    List<@NonNull MemberDeclaration> methods, Range range
) implements Declaration {
    public @Nullable List<@NonNull TypeNode> implementedInterfaces() {
        return implementsNode == null ? null : implementsNode.interfaces();
    }
}
