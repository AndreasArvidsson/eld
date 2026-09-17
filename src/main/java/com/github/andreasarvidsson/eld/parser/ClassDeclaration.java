package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;

public record ClassDeclaration(
    IdentifierDeclaration name, List<@NonNull BlockItem> members, Range range
) implements Declaration {
}
