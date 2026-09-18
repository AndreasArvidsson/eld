package com.github.andreasarvidsson.eld.parser;

import java.util.List;

import org.jspecify.annotations.NonNull;

import com.github.andreasarvidsson.eld.Range;

public record InterfaceDeclaration(
    IdentifierDeclaration name, List<@NonNull TypeNode> superInterfaces,
    List<@NonNull InterfaceMemberDeclaration> members, Range range
) implements Declaration {
}
