package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import java.util.Map;
import java.util.HashMap;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.TypeNode;

public final class SemanticModel {

    private static final String ARROW = " -> ";
    private final IdentityHashMap<MemberExpression, ClassType> memberOwners =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> expressionTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> conversionTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> unionMemberTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<TypeNode, Type> resolvedTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<AstNode, Symbol> declarations =
        new IdentityHashMap<>();
    private final IdentityHashMap<IdentifierExpression, Symbol> references =
        new IdentityHashMap<>();
    private final IdentityHashMap<FunctionSymbol, List<IdentifierDeclaration>> functionParameters =
        new IdentityHashMap<>();
    private final IdentityHashMap<IdentifierDeclaration, IdentifierDeclaration> namedArguments =
        new IdentityHashMap<>();
    private final IdentityHashMap<CallExpression, List<Integer>> argumentParameters =
        new IdentityHashMap<>();
    private final Map<ClassType, FunctionType> constructors = new HashMap<>();

    public void setConstructor(final ClassType owner, final FunctionType type) {
        constructors.put(owner, type);
    }
    public FunctionType getConstructor(final ClassType owner) {
        return Objects.requireNonNull(constructors.get(owner));
    }
    public void setConstructorSymbol(
        final ConstructorDeclaration declaration,
        final ConstructorSymbol symbol
    ) {
        declarations.put(declaration, symbol);
    }

    public void setMemberOwner(
        final MemberExpression expression,
        final ClassType owner
    ) {
        memberOwners.put(expression, owner);
    }

    public ClassType getMemberOwner(final MemberExpression expression) {
        return Objects.requireNonNull(memberOwners.get(expression));
    }

    public void setExpressionType(
        final Expression expression,
        final Type type
    ) {
        expressionTypes.put(expression, type);
    }

    public Type getExpressionType(final Expression expression) {
        return Objects.requireNonNull(expressionTypes.get(expression));
    }

    public void setConversionType(
        final Expression expression,
        final Type type
    ) {
        conversionTypes.put(expression, type);
    }

    public Type getConversionType(final Expression expression) {
        return Objects.requireNonNull(conversionTypes.get(expression));
    }

    public Type getEffectiveType(final Expression expression) {
        return conversionTypes
            .getOrDefault(expression, getExpressionType(expression));
    }

    public void setResolvedType(final TypeNode typeNode, final Type type) {
        resolvedTypes.put(typeNode, type);
    }

    public void setUnionConversion(
        final Expression expression,
        final Type member,
        final UnionType union
    ) {
        unionMemberTypes.put(expression, member);
        setConversionType(expression, union);
    }

    public Type getUnionMemberType(final Expression expression) {
        return unionMemberTypes
            .getOrDefault(expression, getExpressionType(expression));
    }

    public Type getResolvedType(final TypeNode typeNode) {
        return Objects.requireNonNull(resolvedTypes.get(typeNode));
    }

    public void setSymbol(
        final IdentifierDeclaration declaration,
        final Symbol symbol
    ) {
        declarations.put(declaration, symbol);
    }

    public Symbol getSymbol(final IdentifierDeclaration declaration) {
        return Objects.requireNonNull(declarations.get(declaration));
    }

    public void setReference(
        final IdentifierExpression expression,
        final Symbol symbol
    ) {
        references.put(expression, symbol);
    }

    public Symbol getReference(final IdentifierExpression expression) {
        return Objects.requireNonNull(references.get(expression));
    }

    public void setFunctionParameters(
        final FunctionSymbol function,
        final List<IdentifierDeclaration> parameters
    ) {
        functionParameters.put(function, List.copyOf(parameters));
    }

    public List<IdentifierDeclaration> getFunctionParameters(
        final FunctionSymbol function
    ) {
        return Objects.requireNonNull(functionParameters.get(function));
    }

    public void setNamedArgument(
        final IdentifierDeclaration argument,
        final IdentifierDeclaration parameter
    ) {
        namedArguments.put(argument, parameter);
    }

    public void setArgumentParameters(
        final CallExpression call,
        final List<Integer> parameters
    ) {
        argumentParameters.put(call, List.copyOf(parameters));
    }

    public List<Integer> getArgumentParameters(final CallExpression call) {
        return Objects.requireNonNull(argumentParameters.get(call));
    }

    @Override
    public String toString() {
        final List<String> lines = new ArrayList<>();
        appendSection(lines, "Expression types:", expressionTypes);
        appendSection(lines, "Resolved types:", resolvedTypes);

        appendSection(
            lines,
            "Conversions:",
            conversionTypes,
            (expression, type) -> expressionTypes.get(expression) + ARROW + type
        );
        appendSection(lines, "Declarations:", declarations);
        appendSection(
            lines,
            "References:",
            references,
            (expression, symbol) -> symbol instanceof BuiltinFunctionSymbol
                ? "builtin " + symbol.name()
                : symbol.range().toString()
        );
        appendSection(
            lines,
            "Named arguments:",
            namedArguments,
            (argument, parameter) -> "parameter " + parameter.range()
        );
        return Objects.requireNonNull(String.join("\n", lines).stripTrailing());
    }

    private static void appendSection(
        final List<String> lines,
        final String heading,
        final IdentityHashMap<? extends AstNode, ?> entries
    ) {
        appendSection(
            lines,
            heading,
            entries,
            (node, value) -> String.valueOf(value)
        );
    }

    private static <K extends AstNode, V> void appendSection(
        final List<String> lines,
        final String heading,
        final IdentityHashMap<K, V> entries,
        final BiFunction<K, V, String> formatValue
    ) {
        if (entries.isEmpty()) {
            return;
        }
        lines.add(heading);
        entries.entrySet()
            .stream()
            .sorted(
                Comparator.comparing(
                    entry -> Objects.requireNonNull(entry.getKey()).range()
                )
            )
            .forEach(
                entry -> lines
                    .add(
                        "  " + Objects.requireNonNull(entry.getKey()).range()
                            + ARROW
                            + formatValue
                                .apply(entry.getKey(), entry.getValue())
                    )
            );
        lines.add("");
    }

}
