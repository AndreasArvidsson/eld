package com.github.andreasarvidsson.eld.parser;

public sealed interface Pattern extends AstNode
    permits IdentifierPattern, DiscardPattern, TuplePattern, RecordPattern {
}
