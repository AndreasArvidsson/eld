package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record ElseIfBranch(
    Expression condition, BlockStatement branch, Range range
) implements AstNode {
}
