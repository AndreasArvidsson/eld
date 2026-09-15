package mylang.semantic;

import mylang.parser.Mutability;

public record VariableSymbol(
        String name,
        Type type,
        Mutability mutability) implements Symbol {
}
