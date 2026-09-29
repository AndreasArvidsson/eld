package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;

public record EnumDeclaration(
    IdentifierDeclaration name, @Nullable Implements implementsNode,
    List<@NonNull EnumConstant> constants,
    List<@NonNull MemberDeclaration> members, Range range
) implements Declaration {
}
