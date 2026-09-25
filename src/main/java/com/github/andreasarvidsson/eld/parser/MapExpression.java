package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;

public record MapExpression(List<@NonNull MapElement> elements, Range range)
    implements Expression {
}
