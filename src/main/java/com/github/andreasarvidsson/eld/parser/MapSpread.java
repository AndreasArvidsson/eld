package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record MapSpread(Expression expression, Range range)
    implements MapElement {
}
