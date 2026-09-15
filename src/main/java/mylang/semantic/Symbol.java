package mylang.semantic;

import mylang.Range;

public sealed interface Symbol permits VariableSymbol, FunctionSymbol {

    String name();

    Range range();

    Type type();

}