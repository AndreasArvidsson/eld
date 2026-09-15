package mylang.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;

import mylang.Range;

public record Program(List<@NonNull BlockItem> items, Range range)
    implements AstNode {
}
