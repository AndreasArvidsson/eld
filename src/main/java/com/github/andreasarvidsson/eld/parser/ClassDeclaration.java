package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;

public record ClassDeclaration(
    IdentifierDeclaration name, @Nullable IdentifierExpression superClass,
    List<@NonNull MemberDeclaration> members, Range range
) implements Declaration {
}
