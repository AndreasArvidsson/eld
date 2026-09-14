package mylang.parser;

import java.util.List;
import mylang.lexer.Range;

public record CallExpression(
        Expression callee,
        List<Expression> arguments,
        Range range) implements Expression {
}
