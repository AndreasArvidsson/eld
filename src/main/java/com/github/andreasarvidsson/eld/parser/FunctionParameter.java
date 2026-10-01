package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;
import org.jspecify.annotations.Nullable;

public record FunctionParameter(
    BindingDeclaration name, TypeNode type, boolean optional, boolean namedOnly,
    @Nullable Expression defaultValue, @Nullable String defaultText
) implements AstNode {

    public FunctionParameter(
        final BindingDeclaration name,
        final TypeNode type,
        final boolean optional,
        final boolean namedOnly,
        final @Nullable Expression defaultValue
    ) {
        this(name, type, optional, namedOnly, defaultValue, null);
    }

    public FunctionParameter(BindingDeclaration name, TypeNode type) {
        this(name, type, false, false, null);
    }

    public FunctionParameter(
        BindingDeclaration name,
        TypeNode type,
        boolean optional,
        @Nullable Expression defaultValue
    ) {
        this(name, type, optional, false, defaultValue);
    }

    public boolean omittable() {
        return optional || defaultValue != null;
    }

    public boolean discarded() {
        return name instanceof DiscardDeclaration;
    }

    public IdentifierDeclaration identifier() {
        return (IdentifierDeclaration) name;
    }

    public String displayName(final int index) {
        final String label = name.label();
        return label != null ? label : "parameter " + (index + 1);
    }

    @Override
    public Range range() {
        return name.range()
            .union(defaultValue != null ? defaultValue.range() : type.range());
    }

}
