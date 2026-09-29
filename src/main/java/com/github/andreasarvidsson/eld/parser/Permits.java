package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;

public record Permits(
    Range range, List<@NonNull IdentifierExpression> subClasses
) implements AstNode {
}
