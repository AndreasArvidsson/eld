package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;

public record SwitchExpression(
    Expression subject, List<SwitchBranch> branches,
    @Nullable SwitchElseBranch elseBranch, Range range
) implements Expression {
}
