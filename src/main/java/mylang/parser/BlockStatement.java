package mylang.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;

import mylang.Range;

public record BlockStatement(
        List<@NonNull BlockItem> items,
        Range range) implements Statement {
}
