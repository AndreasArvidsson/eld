package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.ArraySpread;
import com.github.andreasarvidsson.eld.parser.BinaryExpression;
import com.github.andreasarvidsson.eld.parser.BinaryOperator;
import com.github.andreasarvidsson.eld.parser.ObjectSpread;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.GroupingExpression;
import com.github.andreasarvidsson.eld.parser.IfExpression;
import com.github.andreasarvidsson.eld.parser.LambdaExpression;
import com.github.andreasarvidsson.eld.parser.LiteralExpression;
import com.github.andreasarvidsson.eld.parser.LiteralKind;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.MapElement;
import com.github.andreasarvidsson.eld.parser.MapEntry;
import com.github.andreasarvidsson.eld.parser.MapExpression;
import com.github.andreasarvidsson.eld.parser.MapSpread;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.SwitchExpression;
import com.github.andreasarvidsson.eld.parser.TernaryExpression;
import com.github.andreasarvidsson.eld.parser.TupleExpression;
import com.github.andreasarvidsson.eld.parser.UnaryExpression;
import com.github.andreasarvidsson.eld.parser.UnaryOperator;

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
            expectedType instanceof BuiltinType numeric
                && (numeric.isInteger() || numeric.isFloating())
                && numericLiteralExpression(expression, numeric.isInteger())
        ) {
            if (numeric.isInteger()) {
                if (numeric == BuiltinType.I64) {
                    requireIntegerLiteralsFit(expression, numeric);
                    setNumericExpressionType(expression, numeric);
                    return numeric;
                }
                final Integer value = integerConstantI32(expression);
                if (
                    value != null && BigInteger.valueOf(value)
                        .bitLength() >= numeric.bits()
                ) {
                    throw new SemanticException(
                        expression.range(),
                        "Integer constant %s does not fit in %s",
                        value,
                        numeric
                    );
                }
            }
            if (numeric == BuiltinType.F32) {
                setNumericExpressionType(expression, numeric);
                return numeric;
            }
        }
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
            analyzer.requireConstantEquality(keyType, map.range(), "Map key");
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
            expected instanceof FunctionType function
                && expression instanceof MemberExpression member
        ) {
            return expressions
                .analyzeMemberReference(member, context, function);
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
                || (expectedType instanceof BuiltinType numeric
                    && (numeric.isInteger() || numeric.isFloating()))
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
                    || expectedType instanceof InterfaceType
                    || (expectedType instanceof BuiltinType numeric
                        && (numeric.isInteger() || numeric.isFloating())))
                && expression instanceof TernaryExpression ternary
        ) {
            if (
                LiteralType.unwrap(
                    analyzeExpression(ternary.condition(), context)
                ) != BuiltinType.BOOL
            ) {
                throw new SemanticException(
                    ternary.condition().range(),
                    "Ternary condition must be bool"
                );
            }
            final List<Scope> branchScopes = new ArrayList<>();
            for (int branchIndex = 0; branchIndex < 2; branchIndex++) {
                final boolean thenBranch = branchIndex == 0;
                final Expression branch =
                    thenBranch ? ternary.thenBranch() : ternary.elseBranch();
                final Scope branchScope = new Scope(context.scope(), true);
                branchScopes.add(branchScope);
                new TypeNarrowing(model)
                    .condition(ternary.condition(), branchScope, thenBranch);
                final Type actual =
                    analyzeExpression(
                        branch,
                        new SemanticContext(
                            branchScope,
                            context.function(),
                            context.loopDepth(),
                            context.yields(),
                            context.yieldType()
                        ),
                        expected
                    );
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
            branchScopes.forEach(Scope::mergeAssignments);
            model.setExpressionType(ternary, expected);
            return expected;
        }
        if (
            expectedType instanceof ArrayType target
                && expression instanceof ArrayExpression array
                && (requiresContextualElements(target.elementType())
                    || (ConstType.unwrap(
                        target.elementType()
                    ) instanceof BuiltinType numeric
                        && (numeric.isInteger() || numeric.isFloating()))
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

    private static boolean numericLiteralExpression(
        final Expression expression,
        final boolean integer
    ) {
        if (expression instanceof LiteralExpression literal) {
            return literal
                .kind() == (integer ? LiteralKind.INT : LiteralKind.FLOAT);
        }
        if (expression instanceof GroupingExpression grouping) {
            return numericLiteralExpression(grouping.expression(), integer);
        }
        if (expression instanceof UnaryExpression unary) {
            return (unary.operator() == UnaryOperator.PLUS
                || unary.operator() == UnaryOperator.MINUS)
                && numericLiteralExpression(unary.operand(), integer);
        }
        if (expression instanceof BinaryExpression binary) {
            return (binary.operator() == BinaryOperator.ADD
                || binary.operator() == BinaryOperator.SUBTRACT
                || binary.operator() == BinaryOperator.MULTIPLY
                || binary.operator() == BinaryOperator.DIVIDE
                || binary.operator() == BinaryOperator.MODULO)
                && numericLiteralExpression(binary.left(), integer)
                && numericLiteralExpression(binary.right(), integer);
        }
        return false;
    }

    private void setNumericExpressionType(
        final Expression expression,
        final BuiltinType type
    ) {
        if (expression instanceof GroupingExpression grouping) {
            setNumericExpressionType(grouping.expression(), type);
        }
        else if (expression instanceof UnaryExpression unary) {
            setNumericExpressionType(unary.operand(), type);
        }
        else if (expression instanceof BinaryExpression binary) {
            setNumericExpressionType(binary.left(), type);
            setNumericExpressionType(binary.right(), type);
        }
        model.setExpressionType(expression, type);
    }

    private static void requireIntegerLiteralsFit(
        final Expression expression,
        final BuiltinType type
    ) {
        if (expression instanceof UnaryExpression unary) {
            final BigInteger signed =
                SemanticAnalyzerExpressionOperations.simpleIntegerLiteral(unary)
                    ? SemanticAnalyzerExpressions.integerLiteral(unary)
                    : null;
            if (signed != null) {
                if (signed.bitLength() >= type.bits()) {
                    throw new SemanticException(
                        unary.range(),
                        "Integer literal %s does not fit in %s",
                        signed,
                        type
                    );
                }
                return;
            }
            requireIntegerLiteralsFit(unary.operand(), type);
        }
        else if (expression instanceof LiteralExpression literal) {
            final BigInteger value = integerConstant(literal);
            if (value != null && value.bitLength() >= type.bits()) {
                throw new SemanticException(
                    literal.range(),
                    "Integer literal %s does not fit in %s",
                    value,
                    type
                );
            }
        }
        else if (expression instanceof GroupingExpression grouping) {
            requireIntegerLiteralsFit(grouping.expression(), type);
        }
        else if (expression instanceof BinaryExpression binary) {
            requireIntegerLiteralsFit(binary.left(), type);
            requireIntegerLiteralsFit(binary.right(), type);
        }
    }

    public static @Nullable BigInteger integerConstant(
        final Expression expression
    ) {
        if (
            expression instanceof LiteralExpression literal
                && literal.kind() == LiteralKind.INT
        ) {
            return new BigInteger(literal.text().replace("_", ""));
        }
        if (expression instanceof GroupingExpression grouping) {
            return integerConstant(grouping.expression());
        }
        if (expression instanceof UnaryExpression unary) {
            final BigInteger operand = integerConstant(unary.operand());
            if (operand == null) {
                return null;
            }
            return switch (unary.operator()) {
                case PLUS -> operand;
                case MINUS -> operand.negate();
                default -> null;
            };
        }
        if (expression instanceof BinaryExpression binary) {
            final BigInteger left = integerConstant(binary.left());
            final BigInteger right = integerConstant(binary.right());
            if (left == null || right == null) {
                return null;
            }
            return switch (binary.operator()) {
                case ADD -> left.add(right);
                case SUBTRACT -> left.subtract(right);
                case MULTIPLY -> left.multiply(right);
                case DIVIDE -> right.signum() == 0 ? null : left.divide(right);
                case MODULO ->
                    right.signum() == 0 ? null : left.remainder(right);
                default -> null;
            };
        }
        return null;
    }

    public static @Nullable Integer integerConstantI32(
        final Expression expression
    ) {
        final BigInteger literal =
            SemanticAnalyzerExpressionOperations
                .simpleIntegerLiteral(expression)
                    ? SemanticAnalyzerExpressions.integerLiteral(expression)
                    : null;
        if (literal != null) {
            return literal.bitLength() < BuiltinType.I32.bits()
                ? literal.intValue()
                : null;
        }
        if (expression instanceof GroupingExpression grouping) {
            return integerConstantI32(grouping.expression());
        }
        if (expression instanceof UnaryExpression unary) {
            final Integer operand = integerConstantI32(unary.operand());
            if (operand == null) {
                return null;
            }
            return switch (unary.operator()) {
                case PLUS -> operand;
                case MINUS -> -operand;
                default -> null;
            };
        }
        if (expression instanceof BinaryExpression binary) {
            final Integer left = integerConstantI32(binary.left());
            final Integer right = integerConstantI32(binary.right());
            if (left == null || right == null) {
                return null;
            }
            return switch (binary.operator()) {
                case ADD -> left + right;
                case SUBTRACT -> left - right;
                case MULTIPLY -> left * right;
                case DIVIDE -> right == 0 ? null : left / right;
                case MODULO -> right == 0 ? null : left % right;
                default -> null;
            };
        }
        return null;
    }
}
