package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;

public record RecordDeclaration(
    IdentifierDeclaration name, List<@NonNull RecordParameter> parameters,
    List<@NonNull TypeNode> implementedInterfaces,
    List<@NonNull MemberDeclaration> methods, Range range
) implements Declaration {
}
