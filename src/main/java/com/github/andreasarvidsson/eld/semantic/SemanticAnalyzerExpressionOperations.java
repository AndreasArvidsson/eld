package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.AssignmentExpression;
import com.github.andreasarvidsson.eld.parser.BinaryExpression;
import com.github.andreasarvidsson.eld.parser.BinaryOperator;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.GroupingExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.LiteralExpression;
import com.github.andreasarvidsson.eld.parser.LiteralKind;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.PostfixExpression;
import com.github.andreasarvidsson.eld.parser.SliceExpression;
import com.github.andreasarvidsson.eld.parser.SubscriptExpression;
import com.github.andreasarvidsson.eld.parser.ThisExpression;
import com.github.andreasarvidsson.eld.parser.UnaryExpression;
import com.github.andreasarvidsson.eld.parser.UnaryOperator;

public final class SemanticAnalyzerExpressionOperations {
    private final SemanticAnalyzer analyzer;
    private final SemanticAnalyzerExpressions expressions;
    private final SemanticModel model;

    public SemanticAnalyzerExpressionOperations(
        final SemanticAnalyzer analyzer,
        final SemanticAnalyzerExpressions expressions,
        final SemanticModel model
    ) {
        this.analyzer = analyzer;
        this.expressions = expressions;
        this.model = model;
    }

    public Type analyzeIndexExpression(
        final SubscriptExpression subscript,
        final SemanticContext context
    ) {
        final Type target =
            expressions.analyzeExpression(subscript.target(), context);
        if (target instanceof TupleType tuple) {
            expressions.analyzeExpression(subscript.index(), context);
            final BigInteger index = integerLiteral(subscript.index());
            if (
                index == null || index.signum() < 0
                    || index.compareTo(
                        BigInteger.valueOf(tuple.elementTypes().size())
                    ) >= 0
            ) {
                throw new SemanticException(
                    subscript.index().range(),
                    "Tuple index must be an integer literal between 0 and %s",
                    tuple.elementTypes().size() - 1
                );
            }
            return tuple.elementTypes().get(index.intValue());
        }
        if (!(target instanceof ArrayType array)) {
            throw new SemanticException(
                subscript.range(),
                "Subscript requires an array target"
            );
        }
        final Type index =
            expressions.analyzeExpression(subscript.index(), context);
        if (!isValidSubscriptIndex(index)) {
            throw new SemanticException(
                subscript.range(),
                "Subscript requires an i8, i16, or i32 index"
            );
        }
        return array.elementType();
    }

    public Type analyzeSliceExpression(
        final SliceExpression slice,
        final SemanticContext context
    ) {
        final Type target =
            expressions.analyzeExpression(slice.target(), context);
        final Expression start = slice.startIndex();
        final Expression end = slice.endIndex();
        final Type startIndex =
            start == null
                ? null
                : expressions.analyzeExpression(start, context);
        final Type endIndex =
            end == null ? null : expressions.analyzeExpression(end, context);
        if (!(target instanceof ArrayType array)) {
            throw new SemanticException(
                slice.range(),
                "Slicing requires an array target"
            );
        }
        if (startIndex != null && !isValidSubscriptIndex(startIndex)) {
            throw new SemanticException(
                Objects.requireNonNull(start).range(),
                "Slice start index must be an i8, i16, or i32"
            );
        }
        if (endIndex != null && !isValidSubscriptIndex(endIndex)) {
            throw new SemanticException(
                Objects.requireNonNull(end).range(),
                "Slice end index must be an i8, i16, or i32"
            );
        }
        return array;
    }

    private boolean isValidSubscriptIndex(final Type index) {
        return index instanceof BuiltinType builtin && builtin.isInteger()
            && builtin != BuiltinType.I64;
    }

    public Type analyzeAssignmentExpression(
        final AssignmentExpression assignment,
        final SemanticContext context
    ) {
        final Type target =
            expressions.analyzeExpression(assignment.target(), context);
        if (
            !(analyzer.isInConstructor() && context.function() == null
                && unwrap(
                    assignment.target()
                ) instanceof MemberExpression member
                && unwrap(member.target()) instanceof ThisExpression
                && model.getMemberOwner(member)
                    .equals(analyzer.currentInstance())
                && model
                    .getReference(member.member()) instanceof VariableSymbol)
        ) {
            requireWritable(assignment.target());
        }
        final Type value =
            expressions.analyzeExpression(assignment.value(), context, target);
        if (
            analyzer
                .resolveAssignType(value, target, assignment.value()) == null
        ) {
            throw new SemanticException(
                assignment.range(),
                "Cannot assign %s to %s",
                value,
                target
            );
        }
        return target;
    }

    public static Expression unwrap(final Expression expression) {
        return expression instanceof GroupingExpression grouping
            ? unwrap(grouping.expression())
            : expression;
    }

    private void requireWritable(final Expression expression) {
        if (expression instanceof GroupingExpression grouping) {
            requireWritable(grouping.expression());
            return;
        }
        if (
            expression instanceof MemberExpression member
                && model.getReference(
                    member.member()
                ) instanceof VariableSymbol variable
                && variable.mutability() == Mutability.VAR
        ) {
            return;
        }
        if (expression instanceof SubscriptExpression subscript) {
            if (
                model.getExpressionType(subscript.target()) instanceof TupleType
            ) {
                throw new SemanticException(
                    expression.range(),
                    "Tuple elements are not writable"
                );
            }
            return;
        }
        if (
            expression instanceof IdentifierExpression identifier
                && model
                    .getReference(identifier) instanceof VariableSymbol variable
                && variable.mutability() == Mutability.VAR
        ) {
            return;
        }
        throw new SemanticException(
            expression.range(),
            "Expression is not writable"
        );
    }

    public Type analyzePostfixExpression(
        final PostfixExpression postfix,
        final SemanticContext context
    ) {
        final Type type =
            expressions.analyzeExpression(postfix.operand(), context);
        requireWritable(postfix.operand());

        if (!numeric(type)) {
            throw new SemanticException(
                postfix.range(),
                "Increment requires a numeric operand"
            );
        }

        model.setExpressionType(postfix, type);
        return type;
    }

    public Type analyzeBinaryExpression(
        final BinaryExpression binary,
        final SemanticContext context
    ) {
        final Type leftType =
            expressions.analyzeExpression(binary.left(), context);
        final Type rightType =
            expressions.analyzeExpression(binary.right(), context);
        boolean unionEquality = false;
        if (
            binary.operator() == BinaryOperator.EQUAL
                || binary.operator() == BinaryOperator.NOT_EQUAL
        ) {
            if (leftType instanceof UnionType || leftType == BuiltinType.ANY) {
                unionEquality =
                    analyzer.resolveAssignType(
                        rightType,
                        leftType,
                        binary.right()
                    ) != null;
            }
            if (
                !unionEquality && (rightType instanceof UnionType
                    || rightType == BuiltinType.ANY)
            ) {
                unionEquality =
                    analyzer.resolveAssignType(
                        leftType,
                        rightType,
                        binary.left()
                    ) != null;
            }
        }
        Type resolvedType = leftType;
        final boolean compatibleNumbers =
            numeric(leftType) && numeric(rightType);
        final boolean relatedClasses =
            leftType instanceof ClassType leftClass
                && rightType instanceof ClassType rightClass
                && model.commonClassType(leftClass, rightClass) != null;
        final boolean valid = switch (binary.operator()) {
            case AND, OR ->
                leftType == BuiltinType.BOOL && rightType == BuiltinType.BOOL;
            case EQUAL, NOT_EQUAL -> unionEquality || compatibleNumbers
                || ((leftType instanceof InterfaceType
                    || rightType instanceof InterfaceType)
                    && (model.isSubtype(leftType, rightType)
                        || model.isSubtype(rightType, leftType)))
                || relatedClasses
                || (leftType.equals(rightType) && leftType != BuiltinType.VOID);
            case ADD -> compatibleNumbers || (leftType == BuiltinType.STRING
                && rightType == BuiltinType.STRING);
            default -> compatibleNumbers;
        };
        if (!valid) {
            throw new SemanticException(
                binary.range(),
                "Operator %s is not applicable to %s and %s",
                binary.operator(),
                leftType,
                rightType
            );
        }

        // TODO: Extend this to handle more complex type assignability rules, such as
        // subtyping and type coercion.

        if (compatibleNumbers) {
            resolvedType = promotedNumericType(leftType, rightType);
            if (!leftType.equals(resolvedType)) {
                model.setConversionType(binary.left(), resolvedType);
            }
            if (!rightType.equals(resolvedType)) {
                model.setConversionType(binary.right(), resolvedType);
            }
        }

        final Type resultType =
            binary.operator().isBool() ? BuiltinType.BOOL : resolvedType;
        model.setExpressionType(binary, resultType);
        return resultType;
    }

    public Type analyzeUnaryExpression(
        final UnaryExpression unary,
        final SemanticContext context
    ) {
        final BigInteger signedLiteral = integerLiteral(unary);
        if (signedLiteral != null) {
            final Type type = integerType(signedLiteral, unary);
            setLiteralType(unary.operand(), type);
            return type;
        }
        final Type operandType =
            expressions.analyzeExpression(unary.operand(), context);
        switch (unary.operator()) {
            case INCREMENT, DECREMENT -> {
                requireWritable(unary.operand());
                if (!numeric(operandType)) {
                    throw new SemanticException(
                        unary.range(),
                        "Increment requires a numeric operand"
                    );
                }
            }
            case NOT -> {
                if (operandType != BuiltinType.BOOL) {
                    throw new SemanticException(
                        unary.range(),
                        "Logical negation requires bool"
                    );
                }
            }
            case PLUS, MINUS -> {
                if (!numeric(operandType)) {
                    throw new SemanticException(
                        unary.range(),
                        "Unary arithmetic requires a numeric operand"
                    );
                }
            }
        }

        final Type resultType =
            unary.operator() == UnaryOperator.PLUS
                || unary.operator() == UnaryOperator.MINUS
                    ? promotedNumericType(operandType, BuiltinType.I32)
                    : operandType;
        if (!operandType.equals(resultType)) {
            model.setConversionType(unary.operand(), resultType);
        }
        model.setExpressionType(unary, resultType);
        return resultType;
    }

    public Type analyzeIdentifierExpression(
        final IdentifierExpression identifier,
        final SemanticContext context
    ) {
        final @Nullable Symbol symbol =
            context.scope().resolve(identifier.name());

        if (symbol == null) {
            throw new SemanticException(
                identifier.range(),
                "Undefined identifier: '%s'",
                identifier.name()
            );
        }

        final Type type = symbol.type();
        model.setExpressionType(identifier, type);
        model.setReference(identifier, symbol);
        return type;
    }

    // Java binary numeric promotion: double, float, long, otherwise int.
    private static BuiltinType promotedNumericType(
        final Type left,
        final Type right
    ) {
        if (left == BuiltinType.F64 || right == BuiltinType.F64) {
            return BuiltinType.F64;
        }
        if (left == BuiltinType.F32 || right == BuiltinType.F32) {
            return BuiltinType.F32;
        }
        if (left == BuiltinType.I64 || right == BuiltinType.I64) {
            return BuiltinType.I64;
        }
        return BuiltinType.I32;
    }

    private static boolean numeric(final Type type) {
        return type instanceof BuiltinType builtin
            && (builtin.isInteger() || builtin.isFloating()
                || builtin == BuiltinType.CHAR);
    }

    public static boolean isFloatingLiteral(final Expression expression) {
        if (expression instanceof LiteralExpression literal) {
            return literal.kind() == LiteralKind.FLOAT;
        }
        if (expression instanceof GroupingExpression grouping) {
            return isFloatingLiteral(grouping.expression());
        }
        return expression instanceof UnaryExpression unary
            && (unary.operator() == UnaryOperator.PLUS
                || unary.operator() == UnaryOperator.MINUS)
            && isFloatingLiteral(unary.operand());
    }

    public static @Nullable BigInteger integerLiteral(
        final Expression expression
    ) {
        if (
            expression instanceof LiteralExpression literal
                && literal.kind() == LiteralKind.INT
        ) {
            return new BigInteger(literal.text().replace("_", ""));
        }
        if (expression instanceof GroupingExpression grouping) {
            return integerLiteral(grouping.expression());
        }
        if (
            expression instanceof UnaryExpression unary
                && (unary.operator() == UnaryOperator.MINUS
                    || unary.operator() == UnaryOperator.PLUS)
        ) {
            final BigInteger value = integerLiteral(unary.operand());
            return value == null
                ? null
                : unary.operator() == UnaryOperator.MINUS
                    ? value.negate()
                    : value;
        }
        return null;
    }

    private static Type integerType(
        final BigInteger value,
        final Expression expression
    ) {
        if (value.bitLength() < 32) {
            return BuiltinType.I32;
        }
        if (value.bitLength() < 64) {
            return BuiltinType.I64;
        }
        throw new SemanticException(
            expression.range(),
            "Integer literal is outside the i64 range"
        );
    }

    private void setLiteralType(final Expression expression, final Type type) {
        model.setExpressionType(expression, type);
        if (expression instanceof GroupingExpression grouping) {
            setLiteralType(grouping.expression(), type);
        }
        if (expression instanceof UnaryExpression unary) {
            setLiteralType(unary.operand(), type);
        }
    }

    public Type analyzeLiteralExpression(final LiteralExpression literal) {
        final Type type = switch (literal.kind()) {
            case INT -> integerType(
                new BigInteger(literal.text().replace("_", "")),
                literal
            );
            case FLOAT -> BuiltinType.F64;
            case CHAR -> BuiltinType.CHAR;
            case BOOL -> BuiltinType.BOOL;
            case STRING, RAW_STRING -> BuiltinType.STRING;
            case NULL -> BuiltinType.NULL;
        };

        model.setExpressionType(literal, type);

        return type;
    }

    public ArrayType analyzeArrayExpression(
        final ArrayExpression array,
        final SemanticContext context
    ) {
        final List<@NonNull Type> elementTypes = new ArrayList<>();

        for (final Expression element : array.elements()) {
            elementTypes.add(expressions.analyzeExpression(element, context));
        }

        // Elements must share a type; class instances can share a base type.
        Type elementType =
            elementTypes.isEmpty()
                ? BuiltinType.NULL
                : Objects.requireNonNull(elementTypes.get(0));

        if (elementType == BuiltinType.VOID) {
            throw new SemanticException(
                array.range(),
                "Array elements must produce values"
            );
        }

        for (final Type type : elementTypes) {
            if (!type.equals(elementType)) {
                if (
                    elementType instanceof ClassType left
                        && type instanceof ClassType right
                ) {
                    final ClassType common = model.commonClassType(left, right);
                    if (common != null) {
                        elementType = common;
                        continue;
                    }
                }
                throw new SemanticException(
                    array.range(),
                    "Array elements must have the same type"
                );
            }
        }

        final ArrayType type = new ArrayType(elementType);
        model.setExpressionType(array, type);
        return type;
    }

}
