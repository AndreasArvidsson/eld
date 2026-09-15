package mylang.parser;

import org.jspecify.annotations.Nullable;

import mylang.Range;

public record ForEachStatement(
    IdentifierDeclaration value, @Nullable IdentifierDeclaration index,
    Expression iterable, BlockStatement body, Range range
) implements Statement {
}
