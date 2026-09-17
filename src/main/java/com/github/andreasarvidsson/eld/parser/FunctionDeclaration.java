package com.github.andreasarvidsson.eld.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record FunctionDeclaration(
    IdentifierDeclaration name, List<@NonNull Parameter> parameters,
    @Nullable TypeNode returnType, BlockStatement body, Range range
) implements Declaration {
}
