package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record NewExpression(
    IdentifierExpression className, List<Expression> arguments, Range range
) implements Expression {
}
