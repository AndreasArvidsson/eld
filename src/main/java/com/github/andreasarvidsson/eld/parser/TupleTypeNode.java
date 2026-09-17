package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record TupleTypeNode(List<TypeNode> elementTypes, Range range)
    implements TypeNode {
}
