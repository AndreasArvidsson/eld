package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
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
import com.github.andreasarvidsson.eld.parser.MapElement;
import com.github.andreasarvidsson.eld.parser.MapEntry;
import com.github.andreasarvidsson.eld.parser.MapExpression;
import com.github.andreasarvidsson.eld.parser.MapSpread;
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
        final Type indexed = ConstType.unwrap(target);
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
        if (!(indexed instanceof ArrayType array)) {
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
        final Type sliced = ConstType.unwrap(target);
        final Expression start = slice.startIndex();
        final Expression end = slice.endIndex();
        final Type startIndex =
            start == null
                ? null
                : expressions.analyzeExpression(start, context);
        final Type endIndex =
            end == null ? null : expressions.analyzeExpression(end, context);
        if (!(sliced instanceof ArrayType array)) {
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
        return LiteralType.unwrap(index) instanceof BuiltinType builtin
            && builtin.isInteger()
            && builtin != BuiltinType.I64;
    }

    public Type analyzeAssignmentExpression(
        final AssignmentExpression assignment,
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
            target = variable.type();
            model.setExpressionType(identifier, target);
            model.clearNarrowedType(identifier);
        }
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
                && !(analyzer.isAnalyzingStaticInitializer()
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
            expressions.analyzeExpression(binary.left(), context);
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
        final Type rightType =
            leftType instanceof BuiltinType builtin && builtin.isFloating()
                && isFloatingLiteral(binary.right())
                    ? expressions.analyzeExpression(
                        binary.right(),
                        rightContext,
                        leftType
                    )
                    : expressions
                        .analyzeExpression(binary.right(), rightContext);
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
        final boolean relatedClasses =
            leftValueType instanceof ClassType leftClass
                && rightValueType instanceof ClassType rightClass
                && model.commonClassType(leftClass, rightClass) != null;
        final boolean valid = switch (binary.operator()) {
            case AND, OR -> leftValueType == BuiltinType.BOOL
                && rightValueType == BuiltinType.BOOL;
            case INSTANCEOF -> JavaTypes.isClassType(rightValueType)
                && (leftValueType instanceof UnionType
                    || !(leftValueType instanceof BuiltinType builtin)
                    || builtin == BuiltinType.ANY
                    || builtin == BuiltinType.NULL
                    || builtin == BuiltinType.STRING);
            case EQUAL, NOT_EQUAL -> unionEquality || compatibleNumbers
                || ((leftValueType instanceof InterfaceType
                    || rightValueType instanceof InterfaceType)
                    && (model.isSubtype(leftType, rightType)
                        || model.isSubtype(rightType, leftType)))
                || relatedClasses
                || (leftType.equals(rightType) && leftType != BuiltinType.VOID)
                || ((leftType instanceof LiteralType
                    || rightType instanceof LiteralType)
                    && leftValueType.equals(rightValueType));
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
        final BigInteger signedLiteral =
            simpleIntegerLiteral(unary) ? integerLiteral(unary) : null;
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
                if (LiteralType.unwrap(operandType) != BuiltinType.BOOL) {
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
    private static BuiltinType promotedNumericType(
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

        if (elementTypes.contains(BuiltinType.VOID)) {
            throw new SemanticException(
                array.range(),
                "Array elements must produce values"
            );
        }
        final Type elementType =
            elementTypes.isEmpty()
                ? BuiltinType.NULL
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
