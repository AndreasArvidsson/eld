package com.github.andreasarvidsson.eld.semantic;

/** Builtin functions whose call signatures depend on their arguments. */
public enum BuiltinFunctionType implements Type {
    PRINT,
    DIR,
    HELP;

    @Override
    public String toString() {
        return switch (this) {
            case PRINT -> "(any) => void";
            case DIR -> "(any) => [string]";
            case HELP -> "(any) => void";
        };
    }
}
