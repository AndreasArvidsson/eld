package com.github.andreasarvidsson.eld.parser;

import com.github.andreasarvidsson.eld.Range;

public record CatchClause(
    IdentifierDeclaration name, TypeNode type, BlockStatement body, Range range
) implements AstNode {
}
