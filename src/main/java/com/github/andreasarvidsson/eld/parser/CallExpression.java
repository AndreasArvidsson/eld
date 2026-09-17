package com.github.andreasarvidsson.eld.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;

import com.github.andreasarvidsson.eld.Range;

public record CallExpression(
    Expression callee, List<@NonNull Expression> arguments, Range range
) implements Expression {
}
