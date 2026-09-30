package com.github.andreasarvidsson.eld.semantic;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.runtime.EldArray;
import com.github.andreasarvidsson.eld.runtime.EldObjectArray;

/** The methods exposed by Eld arrays and their Java implementations. */
public final class ArrayMethods {
    private static final TypeParameterType ZIP_ELEMENT =
        new TypeParameterType("U");
    private static final BuiltinMethodRegistry METHODS =
        new BuiltinMethodRegistry(BuiltinMethodRegistry::javaType);
    private static final Map<String, Method> SPECIAL = new HashMap<>();

    static {
        METHODS.register("length", EldArray.class, "length", false, true);
        METHODS.register("isEmpty", EldArray.class, "isEmpty", false, false);
        special("sort", MethodHandle.class);
        special("sortInPlace", MethodHandle.class);
        special("filter", MethodHandle.class);
        special("partition", MethodHandle.class);
        special("map", MethodHandle.class, String.class);
        special("flatMap", MethodHandle.class, String.class);
        special("flatten", String.class);
        special("reverse");
        special("copy");
        special("reverseInPlace");
        special("concat", EldObjectArray.class);
        special("union", EldObjectArray.class);
        special("intersect", EldObjectArray.class);
        special("difference", EldObjectArray.class);
        special("subtract", EldObjectArray.class);
        special("zip", EldArray.class);
        special("distinct");
        special("reduce", MethodHandle.class, Object.class);
        special("contains", Object.class);
        special("index", Object.class);
        special("lastIndex", Object.class);
        special("find", MethodHandle.class);
        special("findLast", MethodHandle.class);
        special("findIndex", MethodHandle.class);
        special("findLastIndex", MethodHandle.class);
        special("any", MethodHandle.class);
        special("all", MethodHandle.class);
        special("none", MethodHandle.class);
        special("count", MethodHandle.class);
        special("join", String.class);
        special("add", Object[].class);
        special("addAll", EldObjectArray.class);
        special("addFront", Object.class);
        special("insertAt", Object.class, int.class);
        special("remove", Object.class);
        special("removeIf", MethodHandle.class);
        special("removeAll", Object.class);
        special("removeAt", int.class);
        try {
            SPECIAL.put("clear", EldArray.class.getMethod("clear"));
        }
        catch (final NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private ArrayMethods() {}

    private static void special(
        final String name,
        final Class<?>... parameters
    ) {
        try {
            SPECIAL.put(name, EldObjectArray.class.getMethod(name, parameters));
        }
        catch (final NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
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
        if (
            name.equals("flatten") && ConstType
                .unwrap(array.elementType()) instanceof ArrayType nested
        ) {
            final FunctionType type = new FunctionType(List.of(), nested);
            return new JavaMethodSymbol(method, type, range, false, false);
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
