package com.github.andreasarvidsson.eld.parser;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;

public record ConstructorDeclaration(
    List<FunctionParameter> parameters, BlockStatement body, Range range
) implements Declaration {

    public boolean hasExplicitSuperCall() {
        return AstTraversal
            .anyMatch(body, node -> node instanceof SuperConstructorCall);
    }

}
