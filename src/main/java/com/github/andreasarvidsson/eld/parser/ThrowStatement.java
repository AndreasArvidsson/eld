package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record ThrowStatement(Expression value, Range range)
    implements Statement {
}
