package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record NewExpression(
    IdentifierExpression className, List<TypeNode> typeArguments,
    List<Expression> arguments, Range range
) implements Expression {

    public NewExpression(
        final IdentifierExpression className,
        final List<Expression> arguments,
        final Range range
    ) {
        this(className, List.of(), arguments, range);
    }

}
