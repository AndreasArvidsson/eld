package com.github.andreasarvidsson.eld.semantic;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

public final class Scope {

    final private @Nullable Scope parent;

    private final Map<String, Symbol> symbols = new HashMap<>();

    public Scope(final @Nullable Scope parent) {
        this.parent = parent;
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
}
