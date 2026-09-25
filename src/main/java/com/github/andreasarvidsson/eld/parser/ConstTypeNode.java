package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record ConstTypeNode(TypeNode type, Range range) implements TypeNode {
}
