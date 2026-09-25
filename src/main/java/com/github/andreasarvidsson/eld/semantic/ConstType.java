package com.github.andreasarvidsson.eld.semantic;

public record ConstType(Type type) implements Type {
    public ConstType {
        if (type instanceof ConstType) {
            throw new IllegalArgumentException("Type is already const");
        }
    }

    public static Type unwrap(final Type type) {
        return type instanceof ConstType constant ? constant.type() : type;
    }

    public static boolean isConst(final Type type) {
        return type instanceof ConstType;
    }

    @Override
    public String toString() {
        return "const " + type;
    }
}
