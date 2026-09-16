package mylang.semantic;

import mylang.Range;

public sealed interface Symbol
    permits VariableSymbol, ClassSymbol, FunctionSymbol {

    String name();

    Range range();

    Type type();

}