package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record InterfaceContract(
    List<InterfaceType> superInterfaces, Map<String, VariableSymbol> fields,
    Map<String, FunctionSymbol> methods,
    Map<String, List<FunctionSymbol>> overloads
) {
    public InterfaceContract(
        final List<InterfaceType> superInterfaces,
        final Map<String, VariableSymbol> fields,
        final Map<String, FunctionSymbol> methods
    ) {
        this(
            superInterfaces,
            fields,
            methods,
            methods.entrySet()
                .stream()
                .collect(
                    Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> List.of(entry.getValue())
                    )
                )
        );
    }

    public List<FunctionSymbol> methodOverloads(final String name) {
        return overloads.getOrDefault(name, List.of());
    }

    public List<FunctionSymbol> allMethods() {
        return overloads.values().stream().flatMap(List::stream).toList();
    }
}
