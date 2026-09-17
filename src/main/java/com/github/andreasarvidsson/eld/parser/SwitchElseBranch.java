package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record SwitchElseBranch(SwitchBranchBody body, Range range)
    implements AstNode {
}
