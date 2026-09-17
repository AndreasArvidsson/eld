package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record IdentifierDeclaration(String name, Range range)
    implements Declaration {
}
