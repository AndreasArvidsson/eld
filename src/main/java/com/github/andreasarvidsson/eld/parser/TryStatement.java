package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record TryStatement(
    BlockStatement body, List<CatchClause> catches,
    @Nullable BlockStatement finallyBody, Range range
) implements Statement {
}
