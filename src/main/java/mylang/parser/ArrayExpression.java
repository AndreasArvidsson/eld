package mylang.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;

import mylang.Range;

public record ArrayExpression(
        List<@NonNull Expression> elements,
        Range range) implements Expression {
}
