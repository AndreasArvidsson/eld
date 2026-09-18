package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;

public record LambdaExpression(
    List<@NonNull LambdaParameter> parameters, AstNode body, Range range
) implements Expression {
}
