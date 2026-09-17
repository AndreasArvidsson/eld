package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record TupleExpression(List<Expression> elements, Range range)
    implements Expression {
}
