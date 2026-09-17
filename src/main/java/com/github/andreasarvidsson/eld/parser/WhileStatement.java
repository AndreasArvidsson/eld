package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record WhileStatement(
    Expression condition, BlockStatement body, Range range
) implements Statement {
}
