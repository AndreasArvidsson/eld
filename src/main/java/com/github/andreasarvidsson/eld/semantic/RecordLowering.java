package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.AssignmentExpression;
import com.github.andreasarvidsson.eld.parser.BlockItem;
import com.github.andreasarvidsson.eld.parser.BlockStatement;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.ExpressionStatement;
import com.github.andreasarvidsson.eld.parser.FormatStringExpression;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.LiteralExpression;
import com.github.andreasarvidsson.eld.parser.LiteralKind;
import com.github.andreasarvidsson.eld.parser.MemberDeclaration;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.NamedTypeNode;
import com.github.andreasarvidsson.eld.parser.NewExpression;
import com.github.andreasarvidsson.eld.parser.RecordDeclaration;
import com.github.andreasarvidsson.eld.parser.RecordParameter;
import com.github.andreasarvidsson.eld.parser.ReturnStatement;
import com.github.andreasarvidsson.eld.parser.ThisExpression;
import com.github.andreasarvidsson.eld.parser.UninitializedVariableDeclaration;
import com.github.andreasarvidsson.eld.parser.Visibility;

public final class RecordLowering {
    private RecordLowering() {}

    public static ClassDeclaration lower(final RecordDeclaration record) {
        final Range range = record.range();
        final List<@NonNull MemberDeclaration> members = new ArrayList<>();
        final List<@NonNull BlockItem> assignments = new ArrayList<>();
        final List<@NonNull FunctionParameter> copyParameters =
            new ArrayList<>();
        final List<@NonNull FunctionParameter> constructorParameters =
            new ArrayList<>();
        final List<Expression> copyArguments = new ArrayList<>();

        for (final RecordParameter parameter : record.parameters()) {
            final String componentName = parameter.name().name();
            final Range componentRange = parameter.range();
            constructorParameters.add(
                new FunctionParameter(
                    identifier(componentName, parameter),
                    parameter.type()
                )
            );
            members.add(
                new MemberDeclaration(
                    Visibility.PUBLIC,
                    new UninitializedVariableDeclaration(
                        Mutability.CONST,
                        identifier(componentName, parameter),
                        parameter.type(),
                        componentRange
                    ),
                    componentRange
                )
            );
            final MemberExpression target = member(componentName, parameter);
            final AssignmentExpression assignment =
                new AssignmentExpression(
                    target,
                    expression(componentName, parameter)
                );
            assignments
                .add(new ExpressionStatement(assignment, assignment.range()));
            copyParameters.add(
                new FunctionParameter(
                    identifier(componentName, parameter),
                    parameter.type(),
                    false,
                    member(componentName, parameter)
                )
            );
            copyArguments.add(expression(componentName, parameter));
        }

        members.add(
            new MemberDeclaration(
                Visibility.PUBLIC,
                new ConstructorDeclaration(
                    constructorParameters,
                    new BlockStatement(assignments, range),
                    range
                ),
                range
            )
        );
        final FunctionDeclaration copy =
            new FunctionDeclaration(
                new IdentifierDeclaration("copy", record.name().range()),
                copyParameters,
                new NamedTypeNode(
                    record.name().name(),
                    List.of(),
                    record.name().range()
                ),
                new BlockStatement(
                    List.of(
                        new ReturnStatement(
                            new NewExpression(
                                new IdentifierExpression(
                                    record.name().name(),
                                    record.name().range()
                                ),
                                copyArguments,
                                range
                            ),
                            range
                        )
                    ),
                    range
                ),
                range
            );
        members
            .add(new MemberDeclaration(Visibility.PUBLIC, copy, copy.range()));
        if (!declaresToString(record)) {
            members.add(toStringMethod(record));
        }
        members.addAll(record.methods());
        return new ClassDeclaration(
            record.name(),
            null,
            record.implementedInterfaces(),
            members,
            range
        );
    }

    private static boolean declaresToString(final RecordDeclaration record) {
        return record.methods()
            .stream()
            .map(MemberDeclaration::declaration)
            .filter(FunctionDeclaration.class::isInstance)
            .map(FunctionDeclaration.class::cast)
            .anyMatch(
                method -> method.name().name().equals("toString")
                    && method.parameters().isEmpty()
            );
    }

    private static MemberDeclaration toStringMethod(
        final RecordDeclaration record
    ) {
        final Range range = record.range();
        final List<Expression> parts = new ArrayList<>();
        parts.add(stringLiteral(record.name().name() + "(", range));
        for (int i = 0; i < record.parameters().size(); i++) {
            final RecordParameter parameter = record.parameters().get(i);
            parts.add(
                stringLiteral(
                    (i == 0 ? "" : ", ") + parameter.name().name() + "=",
                    parameter.range()
                )
            );
            parts.add(member(parameter.name().name(), parameter));
        }
        parts.add(stringLiteral(")", range));
        final FunctionDeclaration method =
            new FunctionDeclaration(
                new IdentifierDeclaration("toString", record.name().range()),
                List.of(),
                new NamedTypeNode("string", List.of(), record.name().range()),
                new BlockStatement(
                    List.of(
                        new ReturnStatement(
                            new FormatStringExpression(parts, range),
                            range
                        )
                    ),
                    range
                ),
                range
            );
        return new MemberDeclaration(Visibility.PUBLIC, method, range);
    }

    private static LiteralExpression stringLiteral(
        final String value,
        final Range range
    ) {
        return new LiteralExpression(
            LiteralKind.STRING,
            "\"" + value + "\"",
            range
        );
    }

    private static IdentifierDeclaration identifier(
        final String name,
        final RecordParameter parameter
    ) {
        return new IdentifierDeclaration(name, parameter.name().range());
    }

    private static IdentifierExpression expression(
        final String name,
        final RecordParameter parameter
    ) {
        return new IdentifierExpression(name, parameter.name().range());
    }

    private static MemberExpression member(
        final String name,
        final RecordParameter parameter
    ) {
        return new MemberExpression(
            new ThisExpression(parameter.range()),
            expression(name, parameter),
            parameter.range()
        );
    }
}
