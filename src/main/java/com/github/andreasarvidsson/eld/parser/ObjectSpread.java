package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record ObjectSpread(Expression value, Range range)
    implements ObjectEntry {
}
