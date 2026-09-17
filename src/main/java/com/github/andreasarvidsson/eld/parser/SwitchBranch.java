package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record SwitchBranch(
    List<Expression> matches, SwitchBranchBody body, Range range
) implements AstNode {
}
