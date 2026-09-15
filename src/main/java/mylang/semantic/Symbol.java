package mylang.semantic;

public sealed interface Symbol
        permits VariableSymbol, FunctionSymbol {

    String name();

    Type type();
}