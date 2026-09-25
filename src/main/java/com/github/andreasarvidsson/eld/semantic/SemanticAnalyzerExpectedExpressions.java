package com.github.andreasarvidsson.eld.semantic;

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
import com.github.andreasarvidsson.eld.parser.MapElement;
import com.github.andreasarvidsson.eld.parser.MapEntry;
import com.github.andreasarvidsson.eld.parser.MapExpression;
import com.github.andreasarvidsson.eld.parser.MapSpread;
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
        final @Nullable Type expectedType =
            expected == null ? null : ConstType.unwrap(expected);
        if (
            expression instanceof MapExpression map
                && expectedType instanceof InterfaceType target
                && SemanticAnalyzerExpressionOperations.mapType(target) != null
                && target.javaClass() != null
                && target.javaClass()
                    .isAssignableFrom(java.util.LinkedHashMap.class)
        ) {
            final Type keyType = target.typeArguments().get(0);
            final Type valueType = target.typeArguments().get(1);
            for (final MapElement element : map.elements()) {
                if (element instanceof MapEntry entry) {
                    requireMapElement(entry.key(), context, keyType, "key");
                    requireMapElement(
                        entry.value(),
                        context,
                        valueType,
                        "value"
                    );
                }
                else if (element instanceof MapSpread spread) {
                    final Type source =
                        analyzeExpression(spread.expression(), context);
                    if (
                        SemanticAnalyzerExpressionOperations
                            .mapType(source) == null
                            || analyzer.resolveAssignType(
                                source,
                                target,
                                spread.expression()
                            ) == null
                    ) {
                        throw new SemanticException(
                            spread.range(),
                            "Cannot spread %s into %s",
                            source,
                            target
                        );
                    }
                }
            }
            model.setExpressionType(map, target);
            return expected instanceof ConstType ? expected : target;
        }
        if (
            expression instanceof ArrayExpression array
                && expectedType instanceof InterfaceType list
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
            return expected instanceof ConstType ? expected : list;
        }
        if (
            expected instanceof FunctionType
                && expression instanceof MemberExpression
        ) {
            return expressions
                .analyzeExpressionAsCallee(expression, context, true);
        }
        if (expression instanceof ObjectExpression object) {
            Type target = expectedType;
            if (expectedType instanceof UnionType union) {
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
            return expected instanceof ConstType ? expected : contract;
        }
        if (expression instanceof LambdaExpression lambda) {
            if (
                expectedType instanceof InterfaceType comparator
                    && comparator.javaClass() == java.util.Comparator.class
            ) {
                expressions.analyzeLambdaExpression(
                    lambda,
                    context,
                    JavaTypes.comparatorFunction(comparator)
                );
                model.setExpressionType(lambda, comparator);
                return expected instanceof ConstType ? expected : comparator;
            }
            final Type type =
                expressions.analyzeLambdaExpression(lambda, context, expected);
            model.setExpressionType(lambda, type);
            return type;
        }
        if (
            expectedType == BuiltinType.ANY || expectedType instanceof UnionType
                || expectedType instanceof InterfaceType
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
            expectedType instanceof TupleType target
                && expression instanceof TupleExpression tuple
                && tuple.elements().size() == target.elementTypes().size()
        ) {
            return analyzeTupleExpression(tuple, context, target);
        }
        if (
            expected != null
                && (expectedType instanceof UnionType
                    || expectedType == BuiltinType.ANY
                    || expectedType instanceof InterfaceType)
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
            expectedType instanceof ArrayType target
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
            return expected instanceof ConstType ? expected : target;
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

    private void requireMapElement(
        final Expression expression,
        final SemanticContext context,
        final Type expected,
        final String element
    ) {
        final Type actual = analyzeExpression(expression, context, expected);
        if (analyzer.resolveAssignType(actual, expected, expression) == null) {
            throw new SemanticException(
                expression.range(),
                "Cannot use %s as map %s %s",
                actual,
                element,
                expected
            );
        }
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
        final Type unqualified = ConstType.unwrap(type);
        return unqualified instanceof InterfaceType
            || unqualified instanceof FunctionType
            || (unqualified instanceof TupleType tuple && tuple.elementTypes()
                .stream()
                .anyMatch(
                    SemanticAnalyzerExpectedExpressions::requiresContextualElements
                ))
            || unqualified instanceof UnionType
            || unqualified == BuiltinType.ANY
            || (unqualified instanceof ArrayType array
                && requiresContextualElements(array.elementType()));
    }

    private Type analyzeExpression(
        final Expression expression,
        final SemanticContext context
    ) {
        return expressions.analyzeExpression(expression, context);
    }
}
