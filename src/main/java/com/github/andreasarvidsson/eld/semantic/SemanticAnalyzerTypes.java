package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.ArrayTypeNode;
import com.github.andreasarvidsson.eld.parser.ConstTypeNode;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.FunctionTypeNode;
import com.github.andreasarvidsson.eld.parser.NamedTypeNode;
import com.github.andreasarvidsson.eld.parser.TupleExpression;
import com.github.andreasarvidsson.eld.parser.TupleTypeNode;
import com.github.andreasarvidsson.eld.parser.TypeNode;
import com.github.andreasarvidsson.eld.parser.UnionTypeNode;

public final class SemanticAnalyzerTypes {
    private final SemanticModel model;

    public SemanticAnalyzerTypes(final SemanticModel model) {
        this.model = model;
    }

    public Type resolveType(
        final TypeNode typeNode,
        final SemanticContext context
    ) {
        return switch (typeNode) {
            case NamedTypeNode named -> resolveNamedType(named, context);
            case TupleTypeNode tuple -> {
                final Type type =
                    new TupleType(
                        tuple.elementTypes()
                            .stream()
                            .map(element -> resolveType(element, context))
                            .toList()
                    );
                model.setResolvedType(tuple, type);
                yield type;
            }
            case ArrayTypeNode array -> {
                final Type type =
                    new ArrayType(resolveType(array.elementType(), context));
                model.setResolvedType(array, type);
                yield type;
            }
            case ConstTypeNode constant -> {
                final Type resolved = resolveType(constant.type(), context);
                if (
                    !(resolved instanceof ArrayType)
                        && !(resolved instanceof ClassType)
                        && !(resolved instanceof InterfaceType)
                ) {
                    throw new SemanticException(
                        constant.range(),
                        "Only objects and collections can be const, found %s",
                        resolved
                    );
                }
                final Type type =
                    resolved instanceof ConstType
                        ? resolved
                        : new ConstType(resolved);
                model.setResolvedType(constant, type);
                yield type;
            }
            case FunctionTypeNode function -> {
                final TypeNode returnTypeNode = function.returnType();
                final Type returnType =
                    returnTypeNode == null
                        ? BuiltinType.VOID
                        : resolveType(returnTypeNode, context);
                final List<Type> parameterTypes = new ArrayList<>();
                for (final TypeNode paramTypeNode : function.parameterTypes()) {
                    parameterTypes.add(resolveType(paramTypeNode, context));
                }
                final Type type = new FunctionType(parameterTypes, returnType);
                model.setResolvedType(function, type);
                yield type;
            }
            case UnionTypeNode union -> {
                final List<Type> memberTypes = new ArrayList<>();
                for (final TypeNode memberTypeNode : union.memberTypes()) {
                    memberTypes.add(resolveType(memberTypeNode, context));
                }
                final Type type = UnionType.of(memberTypes);
                model.setResolvedType(union, type);
                yield type;
            }
        };
    }

    public boolean canAssignJavaArgument(
        final Type from,
        final Type to,
        final Expression expression
    ) {
        if (
            model.isSubtype(from, to)
                || (to == BuiltinType.ANY && from != BuiltinType.VOID)
        ) {
            return true;
        }
        if (to instanceof UnionType union) {
            return union.memberTypes()
                .stream()
                .anyMatch(
                    member -> canAssignJavaArgument(from, member, expression)
                );
        }
        if (
            from instanceof BuiltinType source
                && to instanceof BuiltinType target
                && (source.isInteger() || source.isFloating())
                && (target.isInteger() || target.isFloating())
        ) {
            if (
                target.isFloating()
                    && SemanticAnalyzerExpressions.isFloatingLiteral(expression)
            ) {
                return true;
            }
            final BigInteger literal =
                SemanticAnalyzer.integerLiteral(expression);
            if (literal != null && target.isInteger()) {
                return literal.bitLength() < target.bits();
            }
            return (source.isInteger()
                && (target.isFloating() || target.bits() >= source.bits()))
                || (source.isFloating() && target.isFloating()
                    && target.bits() >= source.bits());
        }
        return false;
    }

    public boolean isMoreSpecificJavaParameter(
        final Type candidate,
        final Type other
    ) {
        if (candidate.equals(other)) {
            return false;
        }
        if (model.isSubtype(candidate, other)) {
            return true;
        }
        if (
            candidate instanceof BuiltinType source
                && other instanceof BuiltinType target
                && (source.isInteger() || source.isFloating())
                && (target.isInteger() || target.isFloating())
        ) {
            final boolean widens =
                source.isInteger()
                    ? target.isFloating() || target.bits() >= source.bits()
                    : target.isFloating() && target.bits() >= source.bits();
            final boolean widensBack =
                target.isInteger()
                    ? source.isFloating() || source.bits() >= target.bits()
                    : source.isFloating() && source.bits() >= target.bits();
            return widens && !widensBack;
        }
        return false;
    }

    public @Nullable Type commonType(final List<Type> types) {
        if (
            !types.isEmpty()
                && types.stream().allMatch(types.getFirst()::equals)
        ) {
            return types.getFirst();
        }
        final List<Type> members = new ArrayList<>();
        for (final Type type : types) {
            addCommonTypeMembers(type, members);
        }
        if (members.isEmpty() || members.contains(BuiltinType.VOID)) {
            return null;
        }
        if (members.contains(BuiltinType.ANY)) {
            return BuiltinType.ANY;
        }
        final boolean nullable = members.remove(BuiltinType.NULL);
        final Type common = commonNonNullType(members);
        if (common == null) {
            return nullable && members.isEmpty() ? BuiltinType.NULL : null;
        }
        return nullable
            ? UnionType.of(List.of(common, BuiltinType.NULL))
            : common;
    }

    private static void addCommonTypeMembers(
        final Type type,
        final List<Type> members
    ) {
        if (type instanceof UnionType union) {
            for (final Type member : union.memberTypes()) {
                addCommonTypeMembers(member, members);
            }
        }
        else if (!members.contains(type)) {
            members.add(type);
        }
    }

    private @Nullable Type commonNonNullType(final List<Type> types) {
        if (types.isEmpty()) {
            return null;
        }
        Type result = types.getFirst();
        for (int i = 1; i < types.size(); i++) {
            final Type type = types.get(i);
            if (type.equals(result)) {
                continue;
            }
            if (numeric(result) && numeric(type)) {
                final BuiltinType left = (BuiltinType) result;
                final BuiltinType right = (BuiltinType) type;
                if (left.isInteger() != right.isInteger()) {
                    return null;
                }
                result = promotedNumericType(result, type);
                continue;
            }
            if (
                result instanceof ClassType left
                    && type instanceof ClassType right
            ) {
                final ClassType common = model.commonClassType(left, right);
                if (common != null) {
                    result = common;
                    continue;
                }
            }
            if (JavaTypes.isClassType(result) && JavaTypes.isClassType(type)) {
                result = JavaTypes.classType();
                continue;
            }
            if (model.isSubtype(result, type)) {
                result = type;
                continue;
            }
            if (model.isSubtype(type, result)) {
                continue;
            }
            return null;
        }
        return result;
    }

    private static boolean numeric(final Type type) {
        return type instanceof BuiltinType builtin
            && (builtin.isInteger() || builtin.isFloating());
    }

    private static BuiltinType promotedNumericType(
        final Type left,
        final Type right
    ) {
        final BuiltinType leftBuiltin = (BuiltinType) left;
        final BuiltinType rightBuiltin = (BuiltinType) right;
        return leftBuiltin.bits() >= rightBuiltin.bits()
            ? leftBuiltin
            : rightBuiltin;
    }

    @Nullable
    public Type resolveAssignType(
        final Type from,
        final Type to,
        final Expression fromExpression
    ) {
        if (to instanceof ConstType target) {
            final Type source = ConstType.unwrap(from);
            if (
                resolveAssignType(source, target.type(), fromExpression) != null
            ) {
                return to;
            }
            return null;
        }
        if (
            fromExpression instanceof TupleExpression tuple
                && to instanceof TupleType target
        ) {
            if (tuple.elements().size() != target.elementTypes().size()) {
                return null;
            }
            for (int i = 0; i < tuple.elements().size(); i++) {
                final Expression element = tuple.elements().get(i);
                if (
                    resolveAssignType(
                        model.getExpressionType(element),
                        target.elementTypes().get(i),
                        element
                    ) == null
                ) {
                    return null;
                }
            }
            model.setExpressionType(tuple, to);
            return to;
        }
        if (from.equals(to)) {
            return from;
        }
        if (model.isSubtype(from, to)) {
            if (
                JavaTypes.boxedClass(from) != null
                    && to instanceof InterfaceType
            ) {
                model.setConversionType(fromExpression, to);
            }
            return to;
        }
        if (to == BuiltinType.ANY && from != BuiltinType.VOID) {
            model.setConversionType(fromExpression, to);
            return to;
        }

        if (to instanceof UnionType union) {
            if (union.contains(from)) {
                model.setUnionConversion(fromExpression, from, union);
                return to;
            }
            if (from instanceof UnionType source) {
                for (final Type sourceMember : source.memberTypes()) {
                    final boolean assignable =
                        union.memberTypes()
                            .stream()
                            .anyMatch(
                                member -> model.isSubtype(sourceMember, member)
                            );
                    if (!assignable) {
                        // Numeric coercions between union members still require runtime dispatch.
                        return null;
                    }
                }
                model.setConversionType(fromExpression, to);
                return to;
            }
            for (final Type member : union.memberTypes()) {
                if (
                    (member instanceof ClassType
                        || member instanceof InterfaceType
                        || member instanceof ConstType)
                        && resolveAssignType(
                            from,
                            member,
                            fromExpression
                        ) != null
                ) {
                    model.setUnionConversion(fromExpression, member, union);
                    return to;
                }
            }
            // Exact members take priority above; widening alternatives use a stable order.
            for (final BuiltinType member : BuiltinType.values()) {
                if (
                    from instanceof BuiltinType source
                        && union.memberTypes().contains(member)
                        && source.canWidenTo(member)
                        && resolveAssignType(
                            from,
                            member,
                            fromExpression
                        ) != null
                ) {
                    model.setUnionConversion(fromExpression, member, union);
                    return to;
                }
            }
            // Contextual literal narrowing is a fallback after widening.
            for (final BuiltinType member : BuiltinType.values()) {
                if (
                    from instanceof BuiltinType source
                        && (source.isInteger() || source.isFloating())
                        && union.contains(member)
                        && resolveAssignType(
                            from,
                            member,
                            fromExpression
                        ) != null
                ) {
                    model.setUnionConversion(fromExpression, member, union);
                    return to;
                }
            }
            return null;
        }

        if (from instanceof ConstType) {
            return null;
        }

        if (
            fromExpression instanceof ArrayExpression array
                && to instanceof ArrayType target
        ) {
            for (final Expression element : array.elements()) {
                if (
                    resolveAssignType(
                        model.getExpressionType(element),
                        target.elementType(),
                        element
                    ) == null
                ) {
                    return null;
                }
            }
            model.setExpressionType(array, to);
            return to;
        }
        if (
            from instanceof BuiltinType source
                && to instanceof BuiltinType target
                && (source.isInteger() || source.isFloating())
                && (target.isInteger() || target.isFloating())
        ) {
            if (
                target.isFloating() && SemanticAnalyzerExpressions
                    .isFloatingLiteral(fromExpression)
            ) {
                model.setConversionType(fromExpression, target);
                return target;
            }
            final BigInteger literal =
                SemanticAnalyzer.integerLiteral(fromExpression);
            if (literal != null && target.isInteger()) {
                if (literal.bitLength() >= target.bits()) {
                    return null;
                }
                model.setConversionType(fromExpression, to);
                return to;
            }
            if (
                !((source.isInteger()
                    && (target.isFloating() || target.bits() >= source.bits()))
                    || (source.isFloating() && target.isFloating()
                        && target.bits() >= source.bits()))
            ) {
                return null;
            }
            model.setConversionType(fromExpression, to);
            return to;
        }

        return null;
    }

    public Type resolveNamedType(
        final NamedTypeNode named,
        final SemanticContext context
    ) {
        if (
            named.name().equals("Promise")
                || named.name().equals("PromiseSource")
        ) {
            if (named.typeArguments().size() != 1) {
                throw new SemanticException(
                    named.range(),
                    "%s requires 1 type argument, found %s",
                    named.name(),
                    named.typeArguments().size()
                );
            }
            final Type argument =
                resolveType(named.typeArguments().getFirst(), context);
            final Type type =
                named.name().equals("Promise")
                    ? new PromiseType(argument)
                    : new PromiseSourceType(argument);
            model.setResolvedType(named, type);
            return type;
        }
        final Class<?> javaClass = JavaTypes.findClass(named.name());
        if (
            javaClass != null && context.scope().resolve(named.name()) == null
        ) {
            if (
                named.typeArguments()
                    .size() != javaClass.getTypeParameters().length
            ) {
                throw new SemanticException(
                    named.range(),
                    "%s requires %s type arguments, found %s",
                    named.name(),
                    javaClass.getTypeParameters().length,
                    named.typeArguments().size()
                );
            }
            final List<Type> arguments =
                named.typeArguments()
                    .stream()
                    .map(argument -> resolveType(argument, context))
                    .toList();
            if (arguments.contains(BuiltinType.VOID)) {
                throw new SemanticException(
                    named.range(),
                    "A Java generic type argument must produce a value"
                );
            }
            final InterfaceType type = JavaTypes.type(named.name(), arguments);
            model.setResolvedType(named, type);
            return type;
        }
        if (!named.typeArguments().isEmpty()) {
            throw new SemanticException(
                named.range(),
                "Type %s does not accept type arguments",
                named.name()
            );
        }
        final Type type = switch (named.name()) {
            case "i8" -> BuiltinType.I8;
            case "i16" -> BuiltinType.I16;
            case "i32" -> BuiltinType.I32;
            case "i64" -> BuiltinType.I64;
            case "f32" -> BuiltinType.F32;
            case "f64" -> BuiltinType.F64;
            case "char" -> BuiltinType.CHAR;
            case "bool" -> BuiltinType.BOOL;
            case "string" -> BuiltinType.STRING;
            case "null" -> BuiltinType.NULL;
            case "any" -> BuiltinType.ANY;
            default -> {
                final Symbol symbol = context.scope().resolve(named.name());
                if (symbol instanceof InterfaceSymbol contract) {
                    yield contract.type();
                }
                if (symbol instanceof TypeAliasSymbol alias) {
                    yield alias.type();
                }
                if (!(symbol instanceof ClassDeclarationSymbol classSymbol)) {
                    throw new SemanticException(
                        named.range(),
                        "Unknown type: %s",
                        named.name()
                    );
                }
                yield classSymbol.type();
            }
        };

        model.setResolvedType(named, type);

        return type;
    }

}
