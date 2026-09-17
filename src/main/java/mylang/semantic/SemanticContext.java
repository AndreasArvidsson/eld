package mylang.semantic;

import java.util.List;
import mylang.parser.YieldStatement;
import org.jspecify.annotations.Nullable;

public record SemanticContext(
    Scope scope, @Nullable FunctionSymbol function, int loopDepth,
    @Nullable List<YieldStatement> yields
) {

    public SemanticContext(
        Scope scope,
        @Nullable FunctionSymbol function,
        int loopDepth
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
