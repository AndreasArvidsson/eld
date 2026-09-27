package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.BlockStatement;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.ArrayTypeNode;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.EnumConstant;
import com.github.andreasarvidsson.eld.parser.EnumDeclaration;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.MemberDeclaration;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.NamedTypeNode;
import com.github.andreasarvidsson.eld.parser.NewExpression;
import com.github.andreasarvidsson.eld.parser.ReturnStatement;
import com.github.andreasarvidsson.eld.parser.VariableDeclaration;
import com.github.andreasarvidsson.eld.parser.Visibility;

public final class EnumLowering {
    private EnumLowering() {}

    public static ClassDeclaration lower(final EnumDeclaration declaration) {
        final List<@NonNull MemberDeclaration> members = new ArrayList<>();
        for (final EnumConstant constant : declaration.constants()) {
            final Range range = constant.range();
            members.add(
                new MemberDeclaration(
                    Visibility.PUBLIC,
                    true,
                    new VariableDeclaration(
                        Mutability.CONST,
                        constant.name(),
                        new NamedTypeNode(
                            declaration.name().name(),
                            List.of(),
                            range
                        ),
                        new NewExpression(
                            new IdentifierExpression(
                                declaration.name().name(),
                                range
                            ),
                            constant.arguments(),
                            range
                        ),
                        range
                    ),
                    range
                )
            );
        }
        members.addAll(declaration.members());
        final Range range = declaration.name().range();
        final List<Expression> values =
            declaration.constants()
                .stream()
                .<Expression>map(
                    constant -> new IdentifierExpression(
                        constant.name().name(),
                        constant.name().range()
                    )
                )
                .toList();
        members.add(
            new MemberDeclaration(
                Visibility.PUBLIC,
                true,
                new FunctionDeclaration(
                    false,
                    List.of(),
                    new IdentifierDeclaration("values", range),
                    List.of(),
                    new ArrayTypeNode(
                        new NamedTypeNode(
                            declaration.name().name(),
                            List.of(),
                            range
                        ),
                        range
                    ),
                    new BlockStatement(
                        List.of(
                            new ReturnStatement(
                                new ArrayExpression(values, range),
                                range
                            )
                        ),
                        range
                    ),
                    range
                ),
                range
            )
        );
        members.add(intrinsic("name", "string", false, range));
        members.add(intrinsic("ordinal", "i32", false, range));
        final FunctionDeclaration valueOf =
            new FunctionDeclaration(
                false,
                List.of(),
                new IdentifierDeclaration("valueOf", range),
                List.of(
                    new FunctionParameter(
                        new IdentifierDeclaration("name", range),
                        new NamedTypeNode("string", range)
                    )
                ),
                new NamedTypeNode(declaration.name().name(), range),
                new BlockStatement(List.of(), range),
                range
            );
        members.add(
            new MemberDeclaration(Visibility.PUBLIC, true, valueOf, range)
        );
        if (
            declaration.members()
                .stream()
                .noneMatch(
                    member -> member
                        .declaration() instanceof ConstructorDeclaration
                )
        ) {
            members.add(
                new MemberDeclaration(
                    Visibility.PRIVATE,
                    false,
                    new ConstructorDeclaration(
                        List.of(),
                        new BlockStatement(List.of(), range),
                        range
                    ),
                    range
                )
            );
        }
        return new ClassDeclaration(
            declaration.name(),
            null,
            declaration.implementedInterfaces(),
            members,
            declaration.range()
        );
    }

    private static MemberDeclaration intrinsic(
        final String name,
        final String result,
        final boolean staticMember,
        final Range range
    ) {
        return new MemberDeclaration(
            Visibility.PUBLIC,
            staticMember,
            new FunctionDeclaration(
                false,
                List.of(),
                new IdentifierDeclaration(name, range),
                List.of(),
                new NamedTypeNode(result, range),
                new BlockStatement(List.of(), range),
                range
            ),
            range
        );
    }
}
