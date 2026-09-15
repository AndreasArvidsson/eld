package mylang.semantic;

import java.util.Objects;

import mylang.Range;
import mylang.parser.IdentifierDeclaration;

public record FunctionSymbol(
    IdentifierDeclaration declaration, FunctionType type
) implements Symbol {

    @Override
    public String name() {
        return declaration.name();
    }

    @Override
    public Range range() {
        return declaration.range();
    }

    @Override
    public String toString() {
        return Objects.requireNonNull(
            String.format(
                "FunctionSymbol(name=%s, parameterTypes=%s, returnType=%s)",
                name(),
                type.parameterTypes(),
                type.returnType()
            )
        );
    }
}
