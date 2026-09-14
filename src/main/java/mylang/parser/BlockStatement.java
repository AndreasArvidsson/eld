package mylang.parser;

import java.util.List;
import mylang.lexer.Range;

public record BlockStatement(
        List<Statement> statements,
        Range range) implements Statement {
}
