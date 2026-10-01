package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.Visibility;

public record ConstructorOverload(
    @Nullable ConstructorDeclaration declaration, FunctionSymbol function,
    List<FunctionParameter> parameters, Visibility visibility, int marker
) {
    public FunctionType type() {
        return function.type();
    }
}
