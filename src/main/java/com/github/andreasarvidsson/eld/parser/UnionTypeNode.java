package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record UnionTypeNode(List<TypeNode> memberTypes) implements TypeNode {

    @Override
    public Range range() {
        return memberTypes.get(0).range().union(memberTypes.getLast().range());
    }

    public UnionTypeNode(final List<TypeNode> memberTypes) {
        this.memberTypes = memberTypes;
    }
}
