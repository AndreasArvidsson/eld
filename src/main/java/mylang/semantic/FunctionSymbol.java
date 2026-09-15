package mylang.semantic;

import mylang.parser.IdentifierDeclaration;

public record FunctionSymbol(
        IdentifierDeclaration declaration,
        FunctionType type) implements Symbol {

    @Override
    public String name() {
        return declaration.name();
    }

    @Override
    public Range range() {
        return declaration.range();
    }
}
