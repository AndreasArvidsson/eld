package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record YieldStatement(Expression value, Range range)
    implements Statement {
}
