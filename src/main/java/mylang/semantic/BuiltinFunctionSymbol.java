package mylang.semantic;

import java.util.List;
import mylang.Range;

public record BuiltinFunctionSymbol(String name, FunctionType type)
    implements Symbol {

    public static final BuiltinFunctionSymbol PRINT =
        new BuiltinFunctionSymbol(
            "print",
            new FunctionType(List.of(BuiltinType.STRING), BuiltinType.VOID)
        );

    @Override
    public Range range() {
        // Builtins have no declaration in the source file.
        return new Range(0, 0, 0, 0);
    }
}
