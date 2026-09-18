package com.github.andreasarvidsson.eld.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;

import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Range;

public record InterfaceMethodDeclaration(
    IdentifierDeclaration name, List<@NonNull FunctionParameter> parameters,
    @Nullable TypeNode returnType, Range range
) implements InterfaceMemberDeclaration {
}
