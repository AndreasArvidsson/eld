package mylang.parser;

import java.util.List;
import mylang.lexer.Range;

public record ArrayExpression(
        List<Expression> elements,
        Range range) implements Expression {
}
