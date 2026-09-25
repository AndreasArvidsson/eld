package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record RecordPatternField(
    IdentifierDeclaration component, Pattern target, Range range
) implements AstNode {
}
