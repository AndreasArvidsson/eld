package com.github.andreasarvidsson.eld.parser;

public enum BinaryOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    MODULO,
    EQUAL,
    NOT_EQUAL,
    INSTANCEOF,
    LESS,
    LESS_EQUAL,
    GREATER,
    GREATER_EQUAL,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    UNSIGNED_SHIFT_RIGHT,
    BIT_AND,
    BIT_OR,
    BIT_XOR,
    AND,
    OR;

    public boolean isBool() {
        return switch (this) {
            case EQUAL, NOT_EQUAL, INSTANCEOF, LESS, LESS_EQUAL, GREATER,
                GREATER_EQUAL, AND, OR -> true;

            default -> false;
        };
    }
}
