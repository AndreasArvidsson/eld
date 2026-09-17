package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record NamedTypeNode(String name, Range range) implements TypeNode {
}
