package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record MapEntry(Expression key, Expression value, Range range)
    implements MapElement {
}
