package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;

public sealed interface Symbol permits VariableSymbol, ClassDeclarationSymbol,
    FunctionSymbol, BuiltinFunctionSymbol, ConstructorSymbol, InterfaceSymbol,
    JavaMethodSymbol, JavaClassSymbol, TypeAliasSymbol {

    String name();

    Range range();

    Type type();

}
