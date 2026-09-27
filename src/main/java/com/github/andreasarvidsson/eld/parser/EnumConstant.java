package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record EnumConstant(
    IdentifierDeclaration name, List<Expression> arguments, Range range
) implements AstNode {
}
