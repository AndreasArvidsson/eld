package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.ArraySpread;
import com.github.andreasarvidsson.eld.parser.ObjectSpread;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.GroupingExpression;
import com.github.andreasarvidsson.eld.parser.IfExpression;
import com.github.andreasarvidsson.eld.parser.LambdaExpression;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.SwitchExpression;
import com.github.andreasarvidsson.eld.parser.TernaryExpression;
import com.github.andreasarvidsson.eld.parser.TupleExpression;

public final class SemanticAnalyzerExpectedExpressions {
    private final SemanticAnalyzer analyzer;
    private final SemanticAnalyzerExpressions expressions;
    private final SemanticModel model;

    public SemanticAnalyzerExpectedExpressions(
        final SemanticAnalyzer analyzer,
        final SemanticAnalyzerExpressions expressions,
        final SemanticModel model
    ) {
        this.analyzer = analyzer;
        this.expressions = expressions;
        this.model = model;
    }

    public Type analyzeExpression(
        final Expression expression,
        final SemanticContext context,
        final @Nullable Type expected
    ) {
        if (
            expression instanceof ArrayExpression array
                && expected instanceof InterfaceType list
                && list.javaClass() != null
                && list.javaClass().isAssignableFrom(java.util.ArrayList.class)
                && list.typeArguments().size() == 1
        ) {
            if (
                array.elements()
                    .stream()
                    .anyMatch(ArraySpread.class::isInstance)
            ) {
                throw new SemanticException(
                    array.range(),
                    "Array spread requires an Eld array target"
                );
            }
            final Type elementType = list.typeArguments().getFirst();
            for (final Expression element : array.elements()) {
                final Type actual =
                    analyzeExpression(element, context, elementType);
                if (
                    analyzer
                        .resolveAssignType(actual, elementType, element) == null
                ) {
                    throw new SemanticException(
                        element.range(),
                        "Cannot use %s as list element %s",
                        actual,
                        elementType
                    );
                }
            }
            model.setExpressionType(array, list);
            return list;
        }
        if (
            expected instanceof FunctionType
                && expression instanceof MemberExpression
        ) {
            return expressions
                .analyzeExpressionAsCallee(expression, context, true);
        }
        if (expression instanceof ObjectExpression object) {
            Type target = expected;
            if (expected instanceof UnionType union) {
                final List<Type> contracts =
                    union.memberTypes()
                        .stream()
                        .filter(InterfaceType.class::isInstance)
                        .toList();
                target = contracts.size() == 1 ? contracts.getFirst() : null;
            }
            if (
                target == null && object.members()
                    .stream()
                    .anyMatch(ObjectSpread.class::isInstance)
            ) {
                target = analyzer.inferSpreadObject(object, context);
            }
            if (!(target instanceof InterfaceType contract)) {
                throw new SemanticException(
                    object.range(),
                    "Object literal requires an expected interface type"
                );
            }
            analyzer.analyzeObjectExpression(object, context, contract);
            model.setExpressionType(object, contract);
            return contract;
        }
        if (expression instanceof LambdaExpression lambda) {
            if (
                expected instanceof InterfaceType comparator
                    && comparator.javaClass() == java.util.Comparator.class
            ) {
                expressions.analyzeLambdaExpression(
                    lambda,
                    context,
                    JavaTypes.comparatorFunction(comparator)
                );
                model.setExpressionType(lambda, comparator);
                return comparator;
            }
            final Type type =
                expressions.analyzeLambdaExpression(lambda, context, expected);
            model.setExpressionType(lambda, type);
            return type;
        }
        if (
            expected == BuiltinType.ANY || expected instanceof UnionType
                || expected instanceof InterfaceType
        ) {
            if (expression instanceof IfExpression conditional) {
                final Type type =
                    analyzer
                        .analyzeIfExpression(conditional, context, expected);
                model.setExpressionType(expression, type);
                return type;
            }
            if (expression instanceof SwitchExpression selection) {
                final Type type =
                    analyzer.analyzeSwitchExpression(
                        selection,
                        context,
                        true,
                        expected
                    );
                model.setExpressionType(expression, type);
                return type;
            }
        }
        if (
            expected instanceof TupleType target
                && expression instanceof TupleExpression tuple
                && tuple.elements().size() == target.elementTypes().size()
        ) {
            return analyzeTupleExpression(tuple, context, target);
        }
        if (
            (expected instanceof UnionType || expected == BuiltinType.ANY
                || expected instanceof InterfaceType)
                && expression instanceof TernaryExpression ternary
        ) {
            if (
                analyzeExpression(
                    ternary.condition(),
                    context
                ) != BuiltinType.BOOL
            ) {
                throw new SemanticException(
                    ternary.condition().range(),
                    "Ternary condition must be bool"
                );
            }
            for (final Expression branch : List
                .of(ternary.thenBranch(), ternary.elseBranch())) {
                final Type actual =
                    analyzeExpression(branch, context, expected);
                if (
                    analyzer.resolveAssignType(actual, expected, branch) == null
                ) {
                    throw new SemanticException(
                        branch.range(),
                        "Cannot assign %s to %s",
                        actual,
                        expected
                    );
                }
            }
            model.setExpressionType(ternary, expected);
            return expected;
        }
        if (
            expected instanceof ArrayType target
                && expression instanceof ArrayExpression array
                && (requiresContextualElements(target.elementType())
                    || array.elements()
                        .stream()
                        .anyMatch(ArraySpread.class::isInstance))
        ) {
            for (final Expression element : array.elements()) {
                final Type actual =
                    analyzeExpression(element, context, target.elementType());
                if (
                    analyzer.resolveAssignType(
                        actual,
                        target.elementType(),
                        element
                    ) == null
                ) {
                    throw new SemanticException(
                        element.range(),
                        "Cannot assign %s to %s",
                        actual,
                        target.elementType()
                    );
                }
            }
            model.setExpressionType(array, target);
            return target;
        }
        if (
            expression instanceof GroupingExpression grouping
                && expected != null
        ) {
            final Type type =
                analyzeExpression(grouping.expression(), context, expected);
            model.setExpressionType(grouping, type);
            return type;
        }
        return analyzeExpression(expression, context);
    }

    private Type analyzeTupleExpression(
        final TupleExpression tuple,
        final SemanticContext context,
        final TupleType target
    ) {
        for (int i = 0; i < tuple.elements().size(); i++) {
            final Expression element = tuple.elements().get(i);
            final Type wanted = target.elementTypes().get(i);
            final Type actual = analyzeExpression(element, context, wanted);
            if (analyzer.resolveAssignType(actual, wanted, element) == null) {
                throw new SemanticException(
                    element.range(),
                    "Cannot assign %s to %s",
                    actual,
                    wanted
                );
            }
        }
        model.setExpressionType(tuple, target);
        return target;
    }

    private static boolean requiresContextualElements(final Type type) {
        return type instanceof InterfaceType || type instanceof FunctionType
            || (type instanceof TupleType tuple && tuple.elementTypes()
                .stream()
                .anyMatch(
                    SemanticAnalyzerExpectedExpressions::requiresContextualElements
                ))
            || type instanceof UnionType
            || type == BuiltinType.ANY
            || (type instanceof ArrayType array
                && requiresContextualElements(array.elementType()));
    }

    private Type analyzeExpression(
        final Expression expression,
        final SemanticContext context
    ) {
        return expressions.analyzeExpression(expression, context);
    }
}
