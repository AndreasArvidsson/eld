package com.github.andreasarvidsson.eld.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;

import com.github.andreasarvidsson.eld.Range;

public record ArrayExpression(List<@NonNull Expression> elements, Range range)
    implements Expression {
}
