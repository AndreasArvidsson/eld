package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;

public sealed interface Symbol
    permits VariableSymbol, ClassSymbol, FunctionSymbol, BuiltinFunctionSymbol {

    String name();

    Range range();

    Type type();

}
