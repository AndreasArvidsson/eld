package mylang.semantic;

public record FunctionSymbol(
        String name,
        FunctionType type) implements Symbol {
}
