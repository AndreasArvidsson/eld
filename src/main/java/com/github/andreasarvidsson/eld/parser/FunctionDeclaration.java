package com.github.andreasarvidsson.eld.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record FunctionDeclaration(
    boolean async, List<FunctionModifier> modifiers, IdentifierDeclaration name,
    List<@NonNull FunctionParameter> parameters, @Nullable TypeNode returnType,
    BlockStatement body, Range range
) implements Declaration {
    public boolean constant() {
        return modifiers.contains(FunctionModifier.CONST);
    }

    public boolean finalMethod() {
        return modifiers.contains(FunctionModifier.FINAL);
    }
}
