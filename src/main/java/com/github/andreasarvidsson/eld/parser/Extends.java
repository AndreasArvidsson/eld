package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record Extends(Range range, IdentifierExpression superClass)
    implements AstNode {
}
