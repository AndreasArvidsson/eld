package com.github.andreasarvidsson.eld.semantic;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.Mutability;

public final class Scope {

    final private @Nullable Scope parent;
    private final boolean branchBoundary;
    private final boolean captureBoundary;

    private final Map<String, Symbol> symbols = new HashMap<>();
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
        this.parent = parent;
        this.branchBoundary = branchBoundary;
        this.captureBoundary = captureBoundary;
    }

    public void declare(final Symbol symbol) {
        if (symbols.containsKey(symbol.name())) {
            throw new SemanticException(
                symbol.range(),
                "Symbol already declared: %s",
                symbol.name()
            );
        }

        symbols.put(symbol.name(), symbol);
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
