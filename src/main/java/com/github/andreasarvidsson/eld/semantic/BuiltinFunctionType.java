package com.github.andreasarvidsson.eld.semantic;

/** Builtin functions whose call signatures depend on their arguments. */
public enum BuiltinFunctionType implements Type {
    PRINT;

    @Override
    public String toString() {
        return "BuiltinFunctionType(print)";
    }
}
