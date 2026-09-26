package com.github.andreasarvidsson.eld.semantic;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;

/** Thin source aliases for JDK APIs. */
public final class JavaTypes {
    private static final InterfaceType OBJECT =
        new InterfaceType("any", List.of(), Object.class);
    private static final InterfaceType CLASS =
        new InterfaceType("Type", List.of(BuiltinType.ANY), Class.class);
    private static final List<String> EXCEPTION_PACKAGES =
        List.of(
            "java.lang.",
            "java.lang.invoke.",
            "java.lang.reflect.",
            "java.io.",
            "java.net.",
            "java.net.http.",
            "java.nio.",
            "java.nio.channels.",
            "java.nio.charset.",
            "java.nio.file.",
            "java.security.",
            "java.security.cert.",
            "java.security.spec.",
            "java.sql.",
            "java.text.",
            "java.time.",
            "java.time.format.",
            "java.time.zone.",
            "java.util.",
            "java.util.concurrent.",
            "java.util.regex.",
            "java.util.zip."
        );
    private static final Map<String, Class<?>> CLASSES =
        Map.ofEntries(
            Map.entry("Throwable", Throwable.class),
            Map.entry("Exception", Exception.class),
            Map.entry("Error", Error.class),
            Map.entry("RuntimeException", RuntimeException.class),
            Map.entry("Regex", Pattern.class),
            Map.entry("Matcher", Matcher.class),
            Map.entry("Comparable", Comparable.class),
            Map.entry("Comparator", Comparator.class),
            Map.entry("Class", Class.class),
            Map.entry("Type", Class.class),
            Map.entry("Collection", Collection.class),
            Map.entry("List", List.class),
            Map.entry("Set", Set.class),
            Map.entry("SortedSet", SortedSet.class),
            Map.entry("Map", Map.class),
            Map.entry("SortedMap", SortedMap.class),
            Map.entry("ArrayList", ArrayList.class),
            Map.entry("LinkedList", LinkedList.class),
            Map.entry("HashSet", HashSet.class),
            Map.entry("TreeSet", TreeSet.class),
            Map.entry("HashMap", HashMap.class),
            Map.entry("TreeMap", TreeMap.class)
        );

    public static InterfaceType classType() {
        return CLASS;
    }

    public static InterfaceType classType(final Type represented) {
        return new InterfaceType("Type", List.of(represented), Class.class);
    }

    public static boolean isClassType(final Type type) {
        return ConstType.unwrap(type) instanceof InterfaceType contract
            && contract.javaClass() == Class.class;
    }

    private static final Set<String> METHODS =
        Set.of(
            "compareTo",
            "compare",
            "sort",
            "add",
            "get",
            "set",
            "size",
            "isEmpty",
            "clear",
            "contains",
            "containsKey",
            "containsValue",
            "put",
            "first",
            "last",
            "firstKey",
            "lastKey",
            "comparator",
            "subSet",
            "headSet",
            "tailSet",
            "subMap",
            "headMap",
            "tailMap",
            "keySet",
            "values"
        );

    public static @Nullable Class<?> findClass(String name) {
        final Class<?> known = CLASSES.get(name);
        if (known != null) {
            return known;
        }
        for (final String prefix : EXCEPTION_PACKAGES) {
            try {
                final Class<?> type =
                    Class.forName(
                        prefix + name,
                        false,
                        JavaTypes.class.getClassLoader()
                    );
                if (Throwable.class.isAssignableFrom(type)) {
                    return type;
                }
            }
            catch (ClassNotFoundException ignored) {
                // Continue looking in the standard exception packages.
            }
        }
        return null;
    }

    public static InterfaceType type(String name, List<Type> arguments) {
        return new InterfaceType(name, List.copyOf(arguments), findClass(name));
    }

    public static @Nullable Class<?> boxedClass(Type type) {
        if (!(type instanceof BuiltinType builtin)) {
            return null;
        }
        return switch (builtin) {
            case I8 -> Byte.class;
            case I16 -> Short.class;
            case I32 -> Integer.class;
            case I64 -> Long.class;
            case F32 -> Float.class;
            case F64 -> Double.class;
            case BOOL -> Boolean.class;
            case CHAR -> Character.class;
            case STRING -> String.class;
            default -> null;
        };
    }

    public static boolean hasNaturalOrder(Type type) {
        final Class<?> boxed = boxedClass(type);
        return boxed != null && Comparable.class.isAssignableFrom(boxed);
    }

    /** Resolve only the APIs exposed by these aliases, keeping JDK descriptors intact. */
    public static List<JavaMethodSymbol> methods(
        InterfaceType owner,
        String name,
        int arity,
        Range range
    ) {
        return methods(owner, name, arity, range, false);
    }

    public static List<JavaMethodSymbol> methods(
        final InterfaceType owner,
        final String name,
        final int arity,
        final Range range,
        final boolean isStatic
    ) {
        if (
            !METHODS.contains(name) && owner.javaClass() != Pattern.class
                && owner.javaClass() != Matcher.class
                && (owner.javaClass() == null
                    || !Throwable.class.isAssignableFrom(owner.javaClass()))
        ) {
            return List.of();
        }
        final Class<?> javaClass = owner.javaClass();
        if (javaClass == null) {
            return List.of();
        }
        final Map<FunctionType, JavaMethodSymbol> result =
            new LinkedHashMap<>();
        for (final var method : javaClass.getMethods()) {
            if (
                !method.getName().equals(name) || method.isBridge()
                    || Modifier.isStatic(method.getModifiers()) != isStatic
            ) {
                continue;
            }
            final boolean naturalSort =
                List.class.isAssignableFrom(javaClass) && name.equals("sort")
                    && arity == 0;
            if (
                arity >= 0
                    && method.getParameterCount() != (naturalSort ? 1 : arity)
            ) {
                continue;
            }
            try {
                final FunctionType signature =
                    new FunctionType(
                        naturalSort
                            ? List.of()
                            : Arrays.stream(method.getGenericParameterTypes())
                                .map(parameter -> resolve(parameter, owner))
                                .toList(),
                        (Map.class.isAssignableFrom(javaClass)
                            && (name.equals("get") || name.equals("put")))
                            || name.equals("comparator")
                            || (Throwable.class.isAssignableFrom(javaClass)
                                && (name.equals("getMessage")
                                    || name.equals("getLocalizedMessage")
                                    || name.equals("getCause")))
                            || (javaClass == Matcher.class
                                && name.equals("group")
                                && method.getParameterCount() == 1)
                                    ? UnionType
                                        .of(
                                            List.of(
                                                resolve(
                                                    method
                                                        .getGenericReturnType(),
                                                    owner
                                                ),
                                                BuiltinType.NULL
                                            )
                                        )
                                    : resolve(
                                        method.getGenericReturnType(),
                                        owner
                                    )
                    );
                result.putIfAbsent(
                    signature,
                    new JavaMethodSymbol(method, signature, range)
                );
            }
            catch (final IllegalArgumentException ignored) {
                // Other Java APIs need the general interop layer before they can be exposed.
            }
        }
        return List.copyOf(result.values());
    }

    public static @Nullable JavaMethodSymbol objectMethod(
        final String name,
        final int arity,
        final Range range
    ) {
        if (
            !name.equals("toString") && !name.equals("equals")
                && !name.equals("hashCode")
        ) {
            return null;
        }
        for (final var method : Object.class.getMethods()) {
            if (
                method.getName().equals(name)
                    && (arity < 0 || method.getParameterCount() == arity)
            ) {
                return new JavaMethodSymbol(
                    method,
                    new FunctionType(
                        Arrays.stream(method.getGenericParameterTypes())
                            .map(parameter -> resolve(parameter, OBJECT))
                            .toList(),
                        resolve(method.getGenericReturnType(), OBJECT)
                    ),
                    range
                );
            }
        }
        return null;
    }

    public static Type resolve(
        java.lang.reflect.Type type,
        InterfaceType owner
    ) {
        if (type instanceof TypeVariable<?> variable) {
            final Class<?> javaClass = owner.javaClass();
            if (javaClass != null) {
                final var parameters = javaClass.getTypeParameters();
                for (int i = 0; i < parameters.length; i++) {
                    if (parameters[i].getName().equals(variable.getName())) {
                        return owner.typeArguments().get(i);
                    }
                }
            }
            throw new IllegalArgumentException(
                "Unresolved type parameter: " + type
            );
        }
        if (type instanceof WildcardType wildcard) {
            return resolve(
                wildcard.getLowerBounds().length > 0
                    ? wildcard.getLowerBounds()[0]
                    : wildcard.getUpperBounds()[0],
                owner
            );
        }
        if (type instanceof ParameterizedType parameterized) {
            final Class<?> raw = (Class<?>) parameterized.getRawType();
            if (CLASSES.containsValue(raw)) {
                return new InterfaceType(
                    raw.getSimpleName(),
                    java.util.Arrays
                        .stream(parameterized.getActualTypeArguments())
                        .map(argument -> resolve(argument, owner))
                        .toList(),
                    raw
                );
            }
        }
        if (type instanceof Class<?> cls) {
            if (cls == byte.class || cls == Byte.class) {
                return BuiltinType.I8;
            }
            if (cls == short.class || cls == Short.class) {
                return BuiltinType.I16;
            }
            if (cls == int.class || cls == Integer.class) {
                return BuiltinType.I32;
            }
            if (cls == long.class || cls == Long.class) {
                return BuiltinType.I64;
            }
            if (cls == float.class || cls == Float.class) {
                return BuiltinType.F32;
            }
            if (cls == double.class || cls == Double.class) {
                return BuiltinType.F64;
            }
            if (cls == boolean.class || cls == Boolean.class) {
                return BuiltinType.BOOL;
            }
            if (cls == char.class || cls == Character.class) {
                return BuiltinType.CHAR;
            }
            if (cls == String.class || cls == CharSequence.class) {
                return BuiltinType.STRING;
            }
            for (final var entry : CLASSES.entrySet()) {
                if (entry.getValue() == cls) {
                    return type(entry.getKey(), List.of());
                }
            }
            if (Throwable.class.isAssignableFrom(cls)) {
                return new InterfaceType(cls.getSimpleName(), List.of(), cls);
            }
            if (cls == Object.class) {
                return BuiltinType.ANY;
            }
            if (cls == void.class) {
                return BuiltinType.VOID;
            }
        }
        throw new IllegalArgumentException("Unsupported Java type: " + type);
    }

    public static InterfaceContract contract(InterfaceType type) {
        final Map<String, FunctionSymbol> methods =
            new java.util.LinkedHashMap<>();
        final String name =
            type.name().equals("Comparable") ? "compareTo" : "compare";
        if (
            type.javaClass() == Comparable.class
                || type.javaClass() == java.util.Comparator.class
        ) {
            final JavaMethodSymbol method =
                methods(
                    type,
                    name,
                    name.equals("compareTo") ? 1 : 2,
                    new Range(0, 0, 0, 0)
                ).getFirst();
            methods.put(
                name,
                new FunctionSymbol(
                    new IdentifierDeclaration(name, method.range()),
                    method.type()
                )
            );
        }
        return new InterfaceContract(List.of(), Map.of(), methods);
    }

    private JavaTypes() {}

    public static FunctionType comparatorFunction(
        final InterfaceType comparator
    ) {
        return Objects
            .requireNonNull(contract(comparator).methods().get("compare"))
            .type();
    }
}
