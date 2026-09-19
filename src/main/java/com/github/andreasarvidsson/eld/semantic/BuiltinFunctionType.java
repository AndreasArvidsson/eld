package com.github.andreasarvidsson.eld.semantic;

/** Builtin functions whose call signatures depend on their arguments. */
public enum BuiltinFunctionType implements Type {
    PRINT,
    DIR,
    HELP,
    ARRAY_SORT;

    @Override
    public String toString() {
        switch (this) {
            case PRINT:
                return "(any) => void";
            case DIR:
                return "(any) => [string]";
            case HELP:
                return "(any) => void";
            case ARRAY_SORT:
                return "(array) => void";
            default:
                throw new IllegalStateException("Unexpected value: " + this);
        }
    }
}
