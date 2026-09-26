package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record StaticInitializerDeclaration(BlockStatement body, Range range)
    implements Declaration {
}
