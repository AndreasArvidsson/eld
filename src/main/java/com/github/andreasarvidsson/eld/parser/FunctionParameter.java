package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;
import org.jspecify.annotations.Nullable;

public record FunctionParameter(
    IdentifierDeclaration name, TypeNode type, boolean optional,
    @Nullable Expression defaultValue
) implements AstNode {

    public FunctionParameter(IdentifierDeclaration name, TypeNode type) {
        this(name, type, false, null);
    }

    public boolean omittable() {
        return optional || defaultValue != null;
    }

    @Override
    public Range range() {
        return name.range()
            .union(defaultValue != null ? defaultValue.range() : type.range());
    }

}
