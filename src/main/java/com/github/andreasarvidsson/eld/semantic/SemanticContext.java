package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import com.github.andreasarvidsson.eld.parser.YieldStatement;
import org.jspecify.annotations.Nullable;

public record SemanticContext(
    Scope scope, @Nullable FunctionSymbol function, int loopDepth,
    @Nullable List<YieldStatement> yields, @Nullable Type yieldType
) {

    public SemanticContext(
        final Scope scope,
        final @Nullable FunctionSymbol function,
        final int loopDepth,
        final @Nullable List<YieldStatement> yields
    ) {
        this(scope, function, loopDepth, yields, null);
    }

    public SemanticContext(
        final Scope scope,
        final @Nullable FunctionSymbol function,
        final int loopDepth
    ) {
        this(scope, function, loopDepth, null);
    }

    public boolean isWithinLoop() {
        return loopDepth > 0;
    }

    public boolean isWithinFunction() {
        return function != null;
    }

}
