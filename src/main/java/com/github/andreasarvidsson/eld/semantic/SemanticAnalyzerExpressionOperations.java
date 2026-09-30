package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.IntegerLiterals;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.AssignmentStatement;
import com.github.andreasarvidsson.eld.parser.AssignmentOperator;
import com.github.andreasarvidsson.eld.parser.BinaryExpression;
import com.github.andreasarvidsson.eld.parser.BinaryOperator;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.GroupingExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.LiteralExpression;
import com.github.andreasarvidsson.eld.parser.LiteralKind;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.MapElement;
import com.github.andreasarvidsson.eld.parser.MapEntry;
import com.github.andreasarvidsson.eld.parser.MapExpression;
import com.github.andreasarvidsson.eld.parser.MapSpread;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.PostfixExpression;
import com.github.andreasarvidsson.eld.parser.SliceExpression;
import com.github.andreasarvidsson.eld.parser.SubscriptExpression;
import com.github.andreasarvidsson.eld.parser.TernaryExpression;
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
        final Type indexed = LiteralType.unwrap(ConstType.unwrap(target));
        if (indexed instanceof TupleType tuple) {
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
        if (indexed == BuiltinType.STRING) {
            final Type index =
                expressions.analyzeExpression(subscript.index(), context);
            if (!isValidSubscriptIndex(index)) {
                throw new SemanticException(
                    subscript.range(),
                    "Subscript requires an i8, i16, or i32 index"
                );
            }
            return BuiltinType.CHAR;
        }
        if (!(indexed instanceof ArrayType array)) {
            throw new SemanticException(
                subscript.range(),
                "Subscript requires an array or string target"
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
        final Type sliced = LiteralType.unwrap(ConstType.unwrap(target));
        final Expression start = slice.startIndex();
        final Expression end = slice.endIndex();
        final Type startIndex =
            start == null
                ? null
                : expressions.analyzeExpression(start, context);
        final Type endIndex =
            end == null ? null : expressions.analyzeExpression(end, context);
        if (!(sliced instanceof ArrayType) && sliced != BuiltinType.STRING) {
            throw new SemanticException(
                slice.range(),
                "Slicing requires an array or string target"
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
        return sliced;
    }

    private boolean isValidSubscriptIndex(final Type index) {
        return LiteralType.unwrap(index) instanceof BuiltinType builtin
            && builtin.isInteger()
            && builtin != BuiltinType.I64;
    }

    public Type analyzeAssignmentStatement(
        final AssignmentStatement assignment,
        final SemanticContext context
    ) {
        Type target =
            expressions.analyzeExpression(assignment.target(), context);
        final Expression unwrappedTarget = unwrap(assignment.target());
        VariableSymbol assignedVariable = null;
        if (
            unwrappedTarget instanceof IdentifierExpression identifier && model
                .getReference(identifier) instanceof VariableSymbol variable
        ) {
            assignedVariable = variable;
            if (assignment.operator() == AssignmentOperator.ASSIGN) {
                target = variable.type();
                model.setExpressionType(identifier, target);
                model.clearNarrowedType(identifier);
            }
        }
        if (
            !(assignment.operator() == AssignmentOperator.ASSIGN
                && analyzer.isInConstructor()
                && context.function() == null
                && unwrap(
                    assignment.target()
                ) instanceof MemberExpression member
                && unwrap(member.target()) instanceof ThisExpression
                && model.getMemberOwner(member)
                    .equals(analyzer.currentInstance())
                && model
                    .getReference(member.member()) instanceof VariableSymbol)
                && !(assignment.operator() == AssignmentOperator.ASSIGN
                    && analyzer.isAnalyzingStaticInitializer()
                    && context.function() == null
                    && unwrap(
                        assignment.target()
                    ) instanceof MemberExpression memberExpression
                    && model.isUninitializedStaticField(
                        model.getReference(memberExpression.member())
                    )
                    && Objects.equals(
                        model.getClassMemberOwner(
                            model.getReference(memberExpression.member())
                        ),
                        analyzer.currentAccessClass()
                    ))
        ) {
            requireWritable(assignment.target());
        }
        final boolean shiftAssignment = switch (assignment.operator()) {
            case SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT -> true;
            default -> false;
        };
        final boolean bitwise = switch (assignment.operator()) {
            case BIT_AND, BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT,
                UNSIGNED_SHIFT_RIGHT -> true;
            default -> false;
        };
        final Type expectedValueType;
        if (bitwise && integer(target)) {
            final boolean wideLiteral = wideIntegerLiteral(assignment.value());
            if (shiftAssignment) {
                expectedValueType = wideLiteral ? BuiltinType.I64 : null;
            }
            else if (
                LiteralType.unwrap(ConstType.unwrap(target)) == BuiltinType.I64
                    || wideLiteral
            ) {
                expectedValueType = BuiltinType.I64;
            }
            else {
                expectedValueType = null;
            }
        }
        else {
            expectedValueType = target;
        }
        final Type value =
            expressions.analyzeExpression(
                assignment.value(),
                context,
                expectedValueType
            );
        if (bitwise && (!integer(target) || !integer(value))) {
            throw new SemanticException(
                assignment.range(),
                "Bitwise compound assignment requires integer operands"
            );
        }
        if (bitwise && !shiftAssignment && !value.equals(target)) {
            model.setConversionType(assignment.value(), target);
        }
        if (
            !bitwise && assignment.operator() != AssignmentOperator.ASSIGN
                && (!numeric(target) || !numeric(value))
        ) {
            throw new SemanticException(
                assignment.range(),
                "Compound assignment requires numeric operands"
            );
        }
        if (
            !bitwise && analyzer
                .resolveAssignType(value, target, assignment.value()) == null
        ) {
            throw new SemanticException(
                assignment.range(),
                "Cannot assign %s to %s",
                value,
                target
            );
        }
        if (assignedVariable != null) {
            context.scope().reset(assignedVariable);
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
            expression instanceof MemberExpression member && model.getReference(
                member.member()
            ) instanceof VariableSymbol variable
        ) {
            if (variable.mutability() == Mutability.CONST) {
                throw new SemanticException(
                    expression.range(),
                    "Cannot assign to const member '%s'",
                    variable.name()
                );
            }
            if (model.getExpressionType(member.target()) instanceof ConstType) {
                throw new SemanticException(
                    expression.range(),
                    "Cannot modify a member through a const object"
                );
            }
            return;
        }
        if (expression instanceof SubscriptExpression subscript) {
            if (
                LiteralType.unwrap(
                    ConstType.unwrap(
                        model.getExpressionType(subscript.target())
                    )
                ) == BuiltinType.STRING
            ) {
                throw new SemanticException(
                    expression.range(),
                    "String elements are not writable"
                );
            }
            if (
                model.getExpressionType(subscript.target()) instanceof ConstType
            ) {
                throw new SemanticException(
                    expression.range(),
                    "Cannot modify an element through a const array"
                );
            }
            if (
                ConstType.unwrap(
                    model.getExpressionType(subscript.target())
                ) instanceof TupleType
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
        if (
            isFloatingLiteral(binary.left())
                && !isFloatingLiteral(binary.right())
        ) {
            final Type rightType =
                expressions.analyzeExpression(binary.right(), context);
            final Type leftType =
                rightType instanceof BuiltinType builtin && builtin.isFloating()
                    ? expressions
                        .analyzeExpression(binary.left(), context, rightType)
                    : expressions.analyzeExpression(binary.left(), context);
            return finishBinaryExpression(binary, leftType, rightType);
        }
        final Type leftType =
            isBitwise(binary.operator()) && wideIntegerLiteral(binary.left())
                ? expressions
                    .analyzeExpression(binary.left(), context, BuiltinType.I64)
                : expressions.analyzeExpression(binary.left(), context);
        SemanticContext rightContext = context;
        if (
            binary.operator() == BinaryOperator.AND
                || binary.operator() == BinaryOperator.OR
        ) {
            final Scope rightScope = new Scope(context.scope());
            new TypeNarrowing(model).condition(
                binary.left(),
                rightScope,
                binary.operator() == BinaryOperator.AND
            );
            rightContext =
                new SemanticContext(
                    rightScope,
                    context.function(),
                    context.loopDepth(),
                    context.yields(),
                    context.yieldType()
                );
        }
        final Type rightType;
        if (
            isBitwise(binary.operator()) && wideIntegerLiteral(binary.right())
        ) {
            rightType =
                expressions.analyzeExpression(
                    binary.right(),
                    rightContext,
                    BuiltinType.I64
                );
        }
        else if (
            leftType instanceof BuiltinType builtin && builtin.isFloating()
                && isFloatingLiteral(binary.right())
        ) {
            rightType =
                expressions
                    .analyzeExpression(binary.right(), rightContext, leftType);
        }
        else {
            rightType =
                expressions.analyzeExpression(binary.right(), rightContext);
        }
        return finishBinaryExpression(binary, leftType, rightType);
    }

    private Type finishBinaryExpression(
        final BinaryExpression binary,
        final Type leftType,
        final Type rightType
    ) {
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
        final Type leftValueType =
            LiteralType.unwrap(ConstType.unwrap(leftType));
        final Type rightValueType =
            LiteralType.unwrap(ConstType.unwrap(rightType));
        Type resolvedType = leftValueType;
        final boolean compatibleNumbers =
            numeric(leftType) && numeric(rightType);
        final boolean valid = switch (binary.operator()) {
            case AND, OR -> leftValueType == BuiltinType.BOOL
                && rightValueType == BuiltinType.BOOL;
            case BIT_AND, BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT,
                UNSIGNED_SHIFT_RIGHT -> integer(leftType) && integer(rightType);
            case INSTANCEOF -> JavaTypes.isClassType(rightValueType)
                && (leftValueType instanceof UnionType
                    || !(leftValueType instanceof BuiltinType builtin)
                    || builtin == BuiltinType.ANY
                    || builtin == BuiltinType.NULL
                    || builtin == BuiltinType.STRING);
            case EQUAL, NOT_EQUAL ->
                equalityCompatible(model, leftType, rightType);
            case ADD ->
                compatibleNumbers || (leftValueType == BuiltinType.STRING
                    && rightValueType == BuiltinType.STRING);
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

        if (compatibleNumbers && !isShift(binary.operator())) {
            resolvedType = promotedNumericType(leftType, rightType);
            if (!leftType.equals(resolvedType)) {
                model.setConversionType(binary.left(), resolvedType);
            }
            if (!rightType.equals(resolvedType)) {
                model.setConversionType(binary.right(), resolvedType);
            }
        }
        if (isShift(binary.operator())) {
            resolvedType =
                leftValueType == BuiltinType.I64
                    ? BuiltinType.I64
                    : BuiltinType.I32;
            if (!leftType.equals(resolvedType)) {
                model.setConversionType(binary.left(), resolvedType);
            }
            if (!rightType.equals(BuiltinType.I32)) {
                model.setConversionType(binary.right(), BuiltinType.I32);
            }
        }

        final Type resultType =
            binary.operator().isBool() ? BuiltinType.BOOL : resolvedType;
        model.setExpressionType(binary, resultType);
        return resultType;
    }

    public static boolean equalityCompatible(
        final SemanticModel model,
        final Type left,
        final Type right
    ) {
        final Type a = LiteralType.unwrap(ConstType.unwrap(left));
        final Type b = LiteralType.unwrap(ConstType.unwrap(right));
        if (a == BuiltinType.VOID || b == BuiltinType.VOID) {
            return false;
        }
        if (a == BuiltinType.NULL) {
            return nullableEqualityType(b);
        }
        if (b == BuiltinType.NULL) {
            return nullableEqualityType(a);
        }
        if (a == BuiltinType.ANY || b == BuiltinType.ANY) {
            return true;
        }
        if (a instanceof UnionType || b instanceof UnionType) {
            final List<Type> leftMembers =
                a instanceof UnionType union ? union.memberTypes() : List.of(a);
            final List<Type> rightMembers =
                b instanceof UnionType union ? union.memberTypes() : List.of(b);
            return leftMembers.stream()
                .filter(member -> member != BuiltinType.NULL)
                .anyMatch(
                    member -> rightMembers.stream()
                        .filter(other -> other != BuiltinType.NULL)
                        .anyMatch(
                            other -> equalityCompatible(model, member, other)
                        )
                );
        }
        return (numeric(a) && numeric(b)) || model.isSubtype(a, b)
            || model.isSubtype(b, a);
    }

    private static boolean nullableEqualityType(final Type type) {
        return type == BuiltinType.ANY || type == BuiltinType.NULL
            || (type instanceof UnionType union && union.memberTypes()
                .stream()
                .anyMatch(
                    SemanticAnalyzerExpressionOperations::nullableEqualityType
                ));
    }

    public Type analyzeUnaryExpression(
        final UnaryExpression unary,
        final SemanticContext context
    ) {
        final BigInteger signedLiteral =
            simpleIntegerLiteral(unary) ? integerLiteral(unary) : null;
        if (signedLiteral != null) {
            final Type type = integerType(signedLiteral, unary);
            setLiteralType(unary.operand(), type);
            return type;
        }
        final Type operandType =
            unary.operator() == UnaryOperator.BIT_NOT
                && wideIntegerLiteral(unary.operand())
                    ? expressions.analyzeExpression(
                        unary.operand(),
                        context,
                        BuiltinType.I64
                    )
                    : expressions.analyzeExpression(unary.operand(), context);
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
                if (LiteralType.unwrap(operandType) != BuiltinType.BOOL) {
                    throw new SemanticException(
                        unary.range(),
                        "Logical negation requires bool"
                    );
                }
            }
            case BIT_NOT -> {
                if (!integer(operandType)) {
                    throw new SemanticException(
                        unary.range(),
                        "Bitwise negation requires an integer operand"
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
                    : unary.operator() == UnaryOperator.BIT_NOT
                        ? LiteralType.unwrap(operandType) == BuiltinType.I64
                            ? BuiltinType.I64
                            : BuiltinType.I32
                        : LiteralType.unwrap(operandType);
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
        final @Nullable Class<?> javaType =
            symbol == null ? JavaTypes.findClass(identifier.name()) : null;

        if (javaType != null) {
            final List<Type> typeArguments =
                Collections.nCopies(
                    javaType.getTypeParameters().length,
                    BuiltinType.ANY
                );
            final JavaClassSymbol javaClass =
                new JavaClassSymbol(
                    JavaTypes.type(identifier.name(), typeArguments),
                    identifier.range()
                );
            final Type type = JavaTypes.classType(javaClass.type());
            model.setExpressionType(identifier, type);
            model.setReference(identifier, javaClass);
            return type;
        }

        if (symbol == null) {
            throw new SemanticException(
                identifier.range(),
                "Undefined identifier: '%s'",
                identifier.name()
            );
        }

        if (symbol instanceof TypeAliasSymbol) {
            throw new SemanticException(
                identifier.range(),
                "Type alias '%s' cannot be used as a value",
                identifier.name()
            );
        }

        if (
            model.findClassMemberOwner(symbol) != null
                && !model.isStaticMember(symbol)
                && analyzer.currentInstance() == null
        ) {
            throw new SemanticException(
                identifier.range(),
                "Instance member '%s' is not available in a static member",
                identifier.name()
            );
        }

        final Type type =
            symbol instanceof ClassDeclarationSymbol
                || symbol instanceof InterfaceSymbol
                    ? JavaTypes.classType(symbol.type())
                    : symbol instanceof VariableSymbol variable
                        ? context.scope().typeOf(variable)
                        : symbol.type();
        if (symbol instanceof VariableSymbol && !type.equals(symbol.type())) {
            model.setNarrowedType(identifier, type);
        }
        if (
            symbol instanceof VariableSymbol variable
                && variable.mutability() == Mutability.VAR
                && context.function() != null
                && !Objects.equals(
                    model.findVariableOwner(variable),
                    context.function()
                )
        ) {
            model.markCapturedMutable(variable);
        }
        model.setExpressionType(identifier, type);
        model.setReference(identifier, symbol);
        return type;
    }

    // Keep the common integer width; character arithmetic still uses i32.
    public static BuiltinType promotedNumericType(
        final Type left,
        final Type right
    ) {
        return promotedBuiltinNumericType(
            LiteralType.unwrap(left),
            LiteralType.unwrap(right)
        );
    }

    private static BuiltinType promotedBuiltinNumericType(
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
        if (
            left == BuiltinType.I32 || right == BuiltinType.I32
                || left == BuiltinType.CHAR
                || right == BuiltinType.CHAR
        ) {
            return BuiltinType.I32;
        }
        if (left == BuiltinType.I16 || right == BuiltinType.I16) {
            return BuiltinType.I16;
        }
        if (left == BuiltinType.I8 && right == BuiltinType.I8) {
            return BuiltinType.I8;
        }
        return BuiltinType.I32;
    }

    private static boolean numeric(final Type type) {
        return LiteralType.unwrap(type) instanceof BuiltinType builtin
            && (builtin.isInteger() || builtin.isFloating()
                || builtin == BuiltinType.CHAR);
    }

    private static boolean integer(final Type type) {
        return LiteralType
            .unwrap(ConstType.unwrap(type)) instanceof BuiltinType builtin
            && builtin.isInteger();
    }

    private static boolean wideIntegerLiteral(final Expression expression) {
        if (simpleIntegerLiteral(expression)) {
            final BigInteger literal = integerLiteral(expression);
            return literal != null
                && literal.bitLength() >= BuiltinType.I32.bits();
        }
        if (expression instanceof GroupingExpression grouping) {
            return wideIntegerLiteral(grouping.expression());
        }
        if (
            expression instanceof UnaryExpression unary
                && (unary.operator() == UnaryOperator.PLUS
                    || unary.operator() == UnaryOperator.MINUS
                    || unary.operator() == UnaryOperator.BIT_NOT)
        ) {
            return wideIntegerLiteral(unary.operand());
        }
        if (
            expression instanceof BinaryExpression binary
                && switch (binary.operator()) {
                    case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, BIT_AND,
                        BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT,
                        UNSIGNED_SHIFT_RIGHT -> true;
                    default -> false;
                }
        ) {
            return wideIntegerLiteral(binary.left())
                || wideIntegerLiteral(binary.right());
        }
        return expression instanceof TernaryExpression ternary
            && (wideIntegerLiteral(ternary.thenBranch())
                || wideIntegerLiteral(ternary.elseBranch()));
    }

    private static boolean isBitwise(final BinaryOperator operator) {
        return switch (operator) {
            case BIT_AND, BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT,
                UNSIGNED_SHIFT_RIGHT -> true;
            default -> false;
        };
    }

    private static boolean isShift(final BinaryOperator operator) {
        return operator == BinaryOperator.SHIFT_LEFT
            || operator == BinaryOperator.SHIFT_RIGHT
            || operator == BinaryOperator.UNSIGNED_SHIFT_RIGHT;
    }

    public static boolean isFloatingLiteral(final Expression expression) {
        if (expression instanceof LiteralExpression literal) {
            return literal.kind() == LiteralKind.FLOAT;
        }
        if (expression instanceof GroupingExpression grouping) {
            return isFloatingLiteral(grouping.expression());
        }
        if (expression instanceof BinaryExpression binary) {
            return (binary.operator() == BinaryOperator.ADD
                || binary.operator() == BinaryOperator.SUBTRACT
                || binary.operator() == BinaryOperator.MULTIPLY
                || binary.operator() == BinaryOperator.DIVIDE
                || binary.operator() == BinaryOperator.MODULO)
                && isFloatingLiteral(binary.left())
                && isFloatingLiteral(binary.right());
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
            return IntegerLiterals.parse(literal.text());
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

    public static boolean simpleIntegerLiteral(final Expression expression) {
        if (
            expression instanceof LiteralExpression literal
                && literal.kind() == LiteralKind.INT
        ) {
            return true;
        }
        if (expression instanceof GroupingExpression grouping) {
            return simpleIntegerLiteral(grouping.expression());
        }
        return expression instanceof UnaryExpression unary
            && (unary.operator() == UnaryOperator.PLUS
                || unary.operator() == UnaryOperator.MINUS)
            && unsignedIntegerLiteral(unary.operand());
    }

    private static boolean unsignedIntegerLiteral(final Expression expression) {
        if (
            expression instanceof LiteralExpression literal
                && literal.kind() == LiteralKind.INT
        ) {
            return true;
        }
        return expression instanceof GroupingExpression grouping
            && unsignedIntegerLiteral(grouping.expression());
    }

    private static Type integerType(
        final BigInteger value,
        final Expression expression
    ) {
        if (value.bitLength() < 32) {
            return BuiltinType.I32;
        }
        throw new SemanticException(
            expression.range(),
            "Integer literal %s does not fit in inferred type i32; specify an explicit integer type",
            value
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
            case INT ->
                integerType(IntegerLiterals.parse(literal.text()), literal);
            case FLOAT -> BuiltinType.F64;
            case CHAR -> BuiltinType.CHAR;
            case BOOL -> BuiltinType.BOOL;
            case STRING, RAW_STRING, FORMAT_STRING_TEXT,
                RAW_FORMAT_STRING_TEXT -> BuiltinType.STRING;
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

        if (elementTypes.contains(BuiltinType.VOID)) {
            throw new SemanticException(
                array.range(),
                "Array elements must produce values"
            );
        }
        final Type elementType =
            elementTypes.isEmpty()
                ? BuiltinType.ANY
                : analyzer.commonTypeStrict(elementTypes);
        if (elementType == null) {
            throw new SemanticException(
                array.range(),
                "Array elements have no common type"
            );
        }
        for (final Expression element : array.elements()) {
            final Type actual = model.getExpressionType(element);
            if (
                analyzer.resolveAssignType(actual, elementType, element) == null
            ) {
                throw new SemanticException(
                    element.range(),
                    "Cannot use %s as array element %s",
                    actual,
                    elementType
                );
            }
        }

        final ArrayType type = new ArrayType(elementType);
        model.setExpressionType(array, type);
        return type;
    }

    public InterfaceType analyzeMapExpression(
        final MapExpression map,
        final SemanticContext context
    ) {
        final List<@NonNull Type> keyTypes = new ArrayList<>();
        final List<@NonNull Type> valueTypes = new ArrayList<>();
        for (final MapElement element : map.elements()) {
            if (element instanceof MapEntry entry) {
                keyTypes
                    .add(expressions.analyzeExpression(entry.key(), context));
                valueTypes
                    .add(expressions.analyzeExpression(entry.value(), context));
            }
            else if (element instanceof MapSpread spread) {
                final Type source =
                    expressions.analyzeExpression(spread.expression(), context);
                final InterfaceType sourceMap = mapType(source);
                if (sourceMap == null) {
                    throw new SemanticException(
                        spread.range(),
                        "Map spread requires a map, found %s",
                        source
                    );
                }
                keyTypes.add(sourceMap.typeArguments().get(0));
                valueTypes.add(sourceMap.typeArguments().get(1));
            }
        }
        if (keyTypes.isEmpty()) {
            throw new SemanticException(
                map.range(),
                "Cannot infer the type of an empty map literal"
            );
        }
        final Type keyType = requireCommonMapType(keyTypes, map, "keys");
        final Type valueType = requireCommonMapType(valueTypes, map, "values");
        analyzer.requireConstantEquality(keyType, map.range(), "Map key");
        applyMapElementTypes(map, keyType, valueType);
        final InterfaceType type =
            JavaTypes.type("Map", List.of(keyType, valueType));
        model.setExpressionType(map, type);
        return type;
    }

    private Type requireCommonMapType(
        final List<@NonNull Type> types,
        final MapExpression map,
        final String elements
    ) {
        final Type result = analyzer.commonTypeStrict(types);
        if (result == null || result == BuiltinType.VOID) {
            throw new SemanticException(
                map.range(),
                result == BuiltinType.VOID || types.contains(BuiltinType.VOID)
                    ? "Map %s must produce values"
                    : "Map %s have no common type",
                elements
            );
        }
        return result;
    }

    private void applyMapElementTypes(
        final MapExpression map,
        final Type keyType,
        final Type valueType
    ) {
        for (final MapElement element : map.elements()) {
            if (element instanceof MapEntry entry) {
                requireMapElementType(entry.key(), keyType, "key");
                requireMapElementType(entry.value(), valueType, "value");
            }
            else if (element instanceof MapSpread spread) {
                final InterfaceType source =
                    Objects.requireNonNull(
                        mapType(model.getExpressionType(spread.expression()))
                    );
                requireSpreadElementType(
                    spread,
                    source.typeArguments().get(0),
                    keyType,
                    "keys"
                );
                requireSpreadElementType(
                    spread,
                    source.typeArguments().get(1),
                    valueType,
                    "values"
                );
            }
        }
    }

    private void requireMapElementType(
        final Expression expression,
        final Type expected,
        final String element
    ) {
        final Type actual = model.getExpressionType(expression);
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

    private void requireSpreadElementType(
        final MapSpread spread,
        final Type actual,
        final Type expected,
        final String elements
    ) {
        final boolean unionMember =
            expected instanceof UnionType union && union.contains(actual);
        if (
            expected != BuiltinType.ANY && !actual.equals(expected)
                && !unionMember
                && !model.isSubtype(actual, expected)
        ) {
            throw new SemanticException(
                spread.range(),
                "Cannot spread map %s %s as %s",
                elements,
                actual,
                expected
            );
        }
    }

    public static @Nullable InterfaceType mapType(final Type type) {
        if (
            ConstType.unwrap(type) instanceof InterfaceType map
                && map.javaClass() != null
                && java.util.Map.class.isAssignableFrom(map.javaClass())
                && map.typeArguments().size() == 2
        ) {
            return map;
        }
        return null;
    }

}
