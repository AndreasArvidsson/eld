package com.github.andreasarvidsson.eld.semantic;

public enum BuiltinType implements Type {
    I8("i8"),
    I16("i16"),
    I32("i32"),
    I64("i64"),
    F32("f32"),
    F64("f64"),
    BOOL("bool"),
    CHAR("char"),
    STRING("string"),
    NULL("null"),
    ANY("any"),
    VOID("void");

    private final String name;

    private BuiltinType(final String name) {
        this.name = name;
    }

    public boolean isInteger() {
        return this == I8 || this == I16 || this == I32 || this == I64;
    }

    public boolean isFloating() {
        return this == F32 || this == F64;
    }

    public boolean canWidenTo(final BuiltinType target) {
        return (isInteger() && ((target.isInteger() && target.bits() > bits())
            || target.isFloating()))
            || (isFloating() && target.isFloating() && target.bits() > bits());
    }

    public int bits() {
        return switch (this) {
            case I8 -> 8;
            case I16 -> 16;
            case I32, F32 -> 32;
            case I64, F64 -> 64;
            default -> 0;
        };
    }

    @Override
    public String toString() {
        return name;
    }
}
