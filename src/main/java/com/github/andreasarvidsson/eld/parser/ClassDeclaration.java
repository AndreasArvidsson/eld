package com.github.andreasarvidsson.eld.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;

public record ClassDeclaration(
    List<@NonNull ClassModifier> modifiers, IdentifierDeclaration name,
    @Nullable IdentifierExpression superClass,
    List<@NonNull TypeNode> implementedInterfaces,
    List<@NonNull MemberDeclaration> members, Range range
) implements Declaration {
    public ClassDeclaration {
        final var sorted = new ArrayList<>(modifiers);
        Collections.sort(sorted);
        modifiers = Collections.unmodifiableList(sorted);
    }

    public boolean abstractClass() {
        return modifiers.contains(ClassModifier.ABSTRACT);
    }
}
