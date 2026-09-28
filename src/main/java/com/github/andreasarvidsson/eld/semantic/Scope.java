package com.github.andreasarvidsson.eld.semantic;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.Mutability;

public final class Scope {

    final private @Nullable Scope parent;
    private final boolean branchBoundary;
    private final boolean captureBoundary;
    private final boolean classMembers;

    private final Map<String, Symbol> symbols = new HashMap<>();
    private final Map<String, List<FunctionSymbol>> functions = new HashMap<>();
    private final Map<VariableSymbol, Type> narrowedTypes = new HashMap<>();
    private final Set<VariableSymbol> resetTypes = new HashSet<>();
    private final Set<VariableSymbol> assignedVariables = new HashSet<>();

    public Scope(final @Nullable Scope parent) {
        this(parent, false, false);
    }

    public Scope(final @Nullable Scope parent, final boolean branchBoundary) {
        this(parent, branchBoundary, false);
    }

    public Scope(
        final @Nullable Scope parent,
        final boolean branchBoundary,
        final boolean captureBoundary
    ) {
        this(parent, branchBoundary, captureBoundary, false);
    }

    private Scope(
        final @Nullable Scope parent,
        final boolean branchBoundary,
        final boolean captureBoundary,
        final boolean classMembers
    ) {
        this.parent = parent;
        this.branchBoundary = branchBoundary;
        this.captureBoundary = captureBoundary;
        this.classMembers = classMembers;
    }

    public static Scope classMembers() {
        return new Scope(null, false, false, true);
    }

    public void declare(final Symbol symbol) {
        final Symbol existing = symbols.get(symbol.name());
        if (existing != null) {
            if (
                symbol instanceof FunctionSymbol function
                    && (existing instanceof FunctionSymbol
                        || (classMembers && existing instanceof VariableSymbol))
            ) {
                functions.computeIfAbsent(symbol.name(), _ -> new ArrayList<>())
                    .add(function);
                return;
            }
            if (
                classMembers && symbol instanceof VariableSymbol
                    && existing instanceof FunctionSymbol
            ) {
                symbols.put(symbol.name(), symbol);
                return;
            }
            throw new SemanticException(
                symbol.range(),
                "Symbol already declared: %s",
                symbol.name()
            );
        }

        symbols.put(symbol.name(), symbol);
        if (symbol instanceof FunctionSymbol function) {
            functions.put(symbol.name(), new ArrayList<>(List.of(function)));
        }
    }

    public List<FunctionSymbol> functionsLocal(final String name) {
        return List.copyOf(functions.getOrDefault(name, List.of()));
    }

    public List<FunctionSymbol> functions(final String name) {
        if (symbols.containsKey(name)) {
            return functionsLocal(name);
        }
        return parent == null ? List.of() : parent.functions(name);
    }

    public Collection<Symbol> symbols() {
        return Collections.unmodifiableCollection(symbols.values());
    }

    public @Nullable Symbol resolveLocal(final String name) {
        return symbols.get(name);
    }

    public @Nullable Symbol resolve(final String name) {
        final Symbol symbol = symbols.get(name);

        if (symbol != null) {
            return symbol;
        }

        if (parent != null) {
            return parent.resolve(name);
        }

        return null;
    }

    public Type typeOf(final VariableSymbol variable) {
        final Type narrowed = narrowedTypes.get(variable);
        if (narrowed != null) {
            return narrowed;
        }
        if (resetTypes.contains(variable) || parent == null) {
            return variable.type();
        }
        if (captureBoundary && variable.mutability() == Mutability.VAR) {
            return variable.type();
        }
        return parent.typeOf(variable);
    }

    public void narrow(final VariableSymbol variable, final Type type) {
        narrowedTypes.put(variable, type);
        resetTypes.remove(variable);
    }

    public void reset(final VariableSymbol variable) {
        narrowedTypes.remove(variable);
        resetTypes.add(variable);
        assignedVariables.add(variable);
        if (parent != null && !branchBoundary) {
            parent.reset(variable);
        }
    }

    public void mergeAssignments() {
        final @Nullable Scope enclosing = parent;
        if (enclosing != null) {
            assignedVariables.forEach(enclosing::reset);
        }
    }

    public void invalidateCapturedNarrowing(final SemanticModel model) {
        final Set<VariableSymbol> narrowed = new HashSet<>();
        collectNarrowedVariables(narrowed);
        for (final VariableSymbol variable : narrowed) {
            if (
                variable.mutability() == Mutability.VAR
                    && (model.isCapturedMutable(variable)
                        || model.findVariableOwner(variable) == null)
            ) {
                reset(variable);
            }
        }
    }

    private void collectNarrowedVariables(final Set<VariableSymbol> result) {
        result.addAll(narrowedTypes.keySet());
        if (parent != null) {
            parent.collectNarrowedVariables(result);
        }
    }
}
