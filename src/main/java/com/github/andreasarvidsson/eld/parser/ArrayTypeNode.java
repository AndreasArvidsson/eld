package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record ArrayTypeNode(TypeNode elementType, Range range)
    implements TypeNode {
}
