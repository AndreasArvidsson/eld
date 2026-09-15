package mylang.semantic;

import org.jspecify.annotations.Nullable;

public record SemanticContext(
    Scope scope, @Nullable FunctionSymbol function, int loopDepth
) {

    public boolean isWithinLoop() {
        return loopDepth > 0;
    }

    public boolean isWithinFunction() {
        return function != null;
    }

}
