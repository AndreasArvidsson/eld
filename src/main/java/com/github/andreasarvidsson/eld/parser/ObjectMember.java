package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record ObjectMember(
    IdentifierDeclaration name, Expression value, Range range
) implements ObjectEntry {
}
