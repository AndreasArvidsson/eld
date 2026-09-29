package com.github.andreasarvidsson.eld.semantic;

public sealed interface ClassDeclarationSymbol extends Symbol
    permits ClassSymbol, EnumSymbol, RecordSymbol {

    @Override
    ClassType type();
}
