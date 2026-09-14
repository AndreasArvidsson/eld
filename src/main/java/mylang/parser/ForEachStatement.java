package mylang.parser;

import org.jspecify.annotations.Nullable;
import mylang.lexer.Range;

public record ForEachStatement(
        String valueName,
        @Nullable String indexName,
        Expression iterable,
        BlockStatement body,
        Range range) implements Statement {
}
