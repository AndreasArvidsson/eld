package com.github.andreasarvidsson.eld.semantic;

import java.lang.reflect.Method;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.runtime.EldApiMethods;

/** The methods exposed by Eld arrays and their Java implementations. */
public final class ArrayMethods {
    private static final TypeParameterType ZIP_ELEMENT =
        new TypeParameterType("U");
    private static final BuiltinMethodRegistry METHODS =
        new BuiltinMethodRegistry(BuiltinMethodRegistry::javaType);
    private static final Map<String, Method> SPECIAL =
        EldApiMethods.arraySpecialMethods();

    static {
        for (final EldApiMethods.Entry entry : EldApiMethods.arrayMethods()) {
            METHODS.register(
                entry.name(),
                entry.method().getDeclaringClass(),
                entry.method().getName(),
                entry.receiverAsFirstArgument(),
                entry.property(),
                entry.method().getParameterTypes()
            );
        }
    }

    private ArrayMethods() {}

    /** The result of flatten, or null when the receiver does not support it. */
    public static @Nullable ArrayType flattenedType(final ArrayType array) {
        return ConstType.unwrap(array.elementType()) instanceof ArrayType nested
            ? nested
            : null;
    }
    public static List<JavaMethodSymbol> methods(
        final String name,
        final int arity,
        final Range range
    ) {
        return METHODS.methods(name, arity, range);
    }

    public static @Nullable JavaMethodSymbol special(
        final String name,
        final ArrayType array,
        final Range range
    ) {
        final @Nullable Method method = SPECIAL.get(name);
        if (method == null) {
            return null;
        }
        if (name.equals("zip")) {
            final FunctionType type =
                new FunctionType(
                    List.of(new ArrayType(ZIP_ELEMENT)),
                    new ArrayType(
                        new TupleType(List.of(array.elementType(), ZIP_ELEMENT))
                    )
                );
            return new JavaMethodSymbol(method, type, range, false, false);
        }
        if (name.equals("flatten")) {
            final @Nullable ArrayType nested = flattenedType(array);
            if (nested != null) {
                final FunctionType type = new FunctionType(List.of(), nested);
                return new JavaMethodSymbol(method, type, range, false, false);
            }
        }
        final Type result = switch (name) {
            case "removeAt" -> array.elementType();
            case "reverse", "copy", "filter", "concat", "union", "intersect",
                "difference", "subtract", "distinct", "sort" -> array;
            case "partition" -> new TupleType(List.of(array, array));
            case "find", "findLast" ->
                UnionType.of(List.of(array.elementType(), BuiltinType.NULL));
            default -> returnType(method.getReturnType());
        };
        final List<Type> parameters = switch (name) {
            case "concat", "union", "intersect", "difference", "subtract" ->
                List.of(array);
            default -> List.of();
        };
        final FunctionType type = new FunctionType(parameters, result);
        return new JavaMethodSymbol(method, type, range, false, false);
    }

    private static Type returnType(final Class<?> javaType) {
        if (javaType == void.class) {
            return BuiltinType.VOID;
        }
        if (javaType == boolean.class) {
            return BuiltinType.BOOL;
        }
        if (javaType == int.class) {
            return BuiltinType.I32;
        }
        if (javaType == Integer.class) {
            return UnionType.of(List.of(BuiltinType.I32, BuiltinType.NULL));
        }
        if (javaType == String.class) {
            return BuiltinType.STRING;
        }
        return BuiltinType.ANY;
    }

    public static boolean isSpecial(final String name) {
        return SPECIAL.containsKey(name);
    }

    public static boolean mutates(final String name) {
        return switch (name) {
            case "sortInPlace", "reverseInPlace", "add", "addAll", "addFront",
                "insertAt", "remove", "removeIf", "removeAll", "removeAt",
                "clear" -> true;
            default -> false;
        };
    }
}
