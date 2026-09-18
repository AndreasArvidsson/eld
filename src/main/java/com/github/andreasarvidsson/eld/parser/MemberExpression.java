package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record MemberExpression(
    Expression target, IdentifierExpression member, Range range
) implements Expression {
}
