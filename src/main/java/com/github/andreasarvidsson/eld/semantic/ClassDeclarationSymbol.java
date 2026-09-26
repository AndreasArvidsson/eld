package com.github.andreasarvidsson.eld.semantic;

public sealed interface ClassDeclarationSymbol extends Symbol
    permits ClassSymbol, RecordSymbol {

    @Override
    ClassType type();
}
