package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record FormatStringExpression(List<Expression> parts, Range range)
    implements Expression {
}
