package mylang.parser;

import mylang.lexer.Range;

public record DeclarationStatement(
        Declaration declaration,
        Range range) implements Statement {
}
