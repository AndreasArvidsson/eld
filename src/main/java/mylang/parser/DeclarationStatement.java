package mylang.parser;

import mylang.Range;

public record DeclarationStatement(
        Declaration declaration,
        Range range) implements Statement {
}
