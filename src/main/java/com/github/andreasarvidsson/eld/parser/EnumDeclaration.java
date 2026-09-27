package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;

public record EnumDeclaration(
    IdentifierDeclaration name, List<@NonNull TypeNode> implementedInterfaces,
    List<@NonNull EnumConstant> constants,
    List<@NonNull MemberDeclaration> members, Range range
) implements Declaration {
}
