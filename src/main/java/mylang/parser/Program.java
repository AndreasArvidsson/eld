package mylang.parser;

import java.util.List;

import mylang.Range;

public record Program(
        List<Declaration> declarations,
        Range range) implements AstNode {
}
