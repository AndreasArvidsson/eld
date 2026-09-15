package mylang.semantic;

import java.util.Objects;

import mylang.Range;
import mylang.parser.IdentifierDeclaration;
import mylang.parser.Mutability;

public record VariableSymbol(
    IdentifierDeclaration declaration, Type type, Mutability mutability
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
                "VariableSymbol(name=%s, type=%s, mutability=%s)",
                name(),
                type,
                mutability
            )
        );
    }
}
