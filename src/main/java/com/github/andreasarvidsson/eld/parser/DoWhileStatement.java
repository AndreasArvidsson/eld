package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record DoWhileStatement(
    BlockStatement body, Expression condition, Range range
) implements Statement {
}
