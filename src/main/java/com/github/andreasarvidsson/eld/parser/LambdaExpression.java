package com.github.andreasarvidsson.eld.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record LambdaExpression(
    List<@NonNull Parameter> parameters, @Nullable TypeNode returnType,
    AstNode body, Range range
) implements Expression {
}
