package com.github.andreasarvidsson.eld.semantic;

import java.util.List;

import java.util.Map;

public record InterfaceContract(
    List<InterfaceType> superInterfaces, Map<String, VariableSymbol> fields,
    Map<String, FunctionSymbol> methods
) {
}
