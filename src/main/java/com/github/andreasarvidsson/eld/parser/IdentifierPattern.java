package com.github.andreasarvidsson.eld.parser;

import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;

public record IdentifierPattern(
    IdentifierDeclaration name, @Nullable TypeNode type, Range range
) implements Pattern {
}
