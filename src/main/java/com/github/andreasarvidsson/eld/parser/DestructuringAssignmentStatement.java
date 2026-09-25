package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record DestructuringAssignmentStatement(
    Pattern pattern, Expression value, Range range
) implements Statement {
}
