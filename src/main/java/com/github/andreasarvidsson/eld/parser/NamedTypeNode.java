package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;
import java.util.List;

public record NamedTypeNode(
    String name, List<TypeNode> typeArguments, Range range
) implements TypeNode {

    public NamedTypeNode(final String name, final Range range) {
        this(name, List.of(), range);
    }

}
