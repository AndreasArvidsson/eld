package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;

public record BuiltinFunctionSymbol(String name, BuiltinFunctionType type)
    implements Symbol {

    public static final BuiltinFunctionSymbol PRINT =
        new BuiltinFunctionSymbol("print", BuiltinFunctionType.PRINT);

    @Override
    public Range range() {
        // Builtins have no declaration in the source file.
        return new Range(0, 0, 0, 0);
    }
}
