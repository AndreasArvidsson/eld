package com.github.andreasarvidsson.eld.runtime;

import java.lang.reflect.AnnotatedElement;
import java.lang.invoke.MethodType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The explicit API mappings shared by member resolution and runtime introspection. */
public final class EldApiMethods {
    public record Entry(
        String name, Method method, boolean receiverAsFirstArgument,
        boolean property, List<@Nullable String> parameterNames
    ) {
        public Entry(
            final String name,
            final Method method,
            final boolean receiverAsFirstArgument,
            final boolean property
        ) {
            this(
                name,
                method,
                receiverAsFirstArgument,
                property,
                Arrays.stream(method.getParameters())
                    .skip(receiverAsFirstArgument ? 1 : 0)
                    .<@Nullable String>map(
                        parameter -> parameter.isNamePresent()
                            ? parameter.getName()
                            : null
                    )
                    .toList()
            );
        }
    }

    private static final List<Entry> STRING = new ArrayList<>();
    public static final Set<String> OBJECT_METHODS =
        Set.of("hashCode", "toString", "getClass");
    public static final Map<String, Class<?>> CLASSES =
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
    private static final Set<String> METHODS =
        Set.of(
            "compareTo",
            "toString",
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
            "values",
            "getSimpleName"
        );
    static {
        javaProperty("length", "length");
        javaMethod("isEmpty", "isEmpty");
        javaMethod("isBlank", "isBlank");
        javaMethod("startsWith", "startsWith", List.of("prefix"), String.class);
        javaMethod("endsWith", "endsWith", List.of("suffix"), String.class);
        javaMethod("contains", "contains", List.of("part"), CharSequence.class);
        javaMethod("compare", "compareTo", List.of("other"), String.class);
        javaMethod(
            "compareIgnoreCase",
            "compareToIgnoreCase",
            List.of("other"),
            String.class
        );
        javaMethod(
            "equalsIgnoreCase",
            "equalsIgnoreCase",
            List.of("other"),
            String.class
        );
        javaMethod("hashCode", "hashCode");
        javaMethod("upper", "toUpperCase");
        javaMethod("lower", "toLowerCase");
        javaMethod("strip", "strip");
        javaMethod("stripStart", "stripLeading");
        javaMethod("stripEnd", "stripTrailing");
        javaMethod(
            "replaceAll",
            "replace",
            List.of("regex", "replacement"),
            CharSequence.class,
            CharSequence.class
        );
        javaMethod("repeat", "repeat", List.of("count"), int.class);

        eldMethod("count", String.class, String.class);
        eldMethod("index", String.class, String.class);
        eldMethod("index", String.class, String.class, int.class);
        eldMethod("lastIndex", String.class, String.class);
        eldMethod("lastIndex", String.class, String.class, int.class);
        eldMethod("isDigit", String.class);
        eldMethod("isAlnum", String.class);
        eldMethod("isAlpha", String.class);
        eldMethod("isLower", String.class);
        eldMethod("isUpper", String.class);
        eldMethod("bytes", String.class);
        eldMethod("chars", String.class);
        eldMethod("matches", String.class, String.class);
        eldMethod("capitalize", String.class);
        eldMethod("title", String.class);
        eldMethod("strip", String.class, String.class);
        eldMethod("stripStart", String.class, String.class);
        eldMethod("stripEnd", String.class, String.class);
        eldMethod("padStart", String.class, int.class, String.class);
        eldMethod("padEnd", String.class, int.class, String.class);
        eldMethod("replace", String.class, String.class, String.class);
        eldMethod("lines", String.class);
        eldMethod("split", String.class);
        eldMethod("split", String.class, String.class);
        eldMethod("split", String.class, String.class, int.class);
        eldMethod("reverse", String.class);
    }

    public static List<Entry> stringMethods() {
        return List.copyOf(STRING);
    }

    public static List<Entry> arrayMethods() {
        return Arrays.stream(EldArray.class.getMethods())
            .filter(EldApiMethods::runtimeVisible)
            .filter(
                method -> property(method) || method.getName().equals("isEmpty")
            )
            .map(
                method -> new Entry(
                    method.getName(),
                    method,
                    false,
                    property(method)
                )
            )
            .toList();
    }

    public static Map<String, Method> arraySpecialMethods() {
        final Map<String, Method> methods = new HashMap<>();
        Arrays.stream(EldObjectArray.class.getMethods())
            .filter(EldApiMethods::runtimeVisible)
            .filter(
                method -> !property(method)
                    && !method.getName().equals("isEmpty")
                    && !OBJECT_METHODS.contains(method.getName())
            )
            .sorted(
                Comparator.comparingInt(Method::getParameterCount)
                    .reversed()
                    .thenComparing(
                        method -> !Arrays.asList(method.getParameterTypes())
                            .contains(Object[].class)
                    )
                    .thenComparing(
                        method -> !Arrays.asList(method.getParameterTypes())
                            .contains(Object.class)
                    )
                    .thenComparing(Method::toGenericString)
            )
            .forEach(method -> methods.putIfAbsent(method.getName(), method));
        // Keep the interface descriptor used by the compiler for clear().
        Arrays.stream(EldArray.class.getMethods())
            .filter(EldApiMethods::runtimeVisible)
            .filter(
                method -> method.getName().equals("clear")
                    && methods.containsKey("clear")
            )
            .forEach(method -> methods.put(method.getName(), method));
        return Map.copyOf(methods);
    }

    public static boolean runtimeType(final Class<?> type) {
        return type.getPackageName()
            .equals(EldApiMethods.class.getPackageName());
    }

    public static boolean runtimeVisible(final Member member) {
        return Modifier.isPublic(member.getModifiers()) && !member.isSynthetic()
            && !member.getName().startsWith("$")
            && member.getDeclaringClass() != Object.class
            && (!runtimeType(member.getDeclaringClass()) || apiMember(member));
    }

    public static boolean apiMember(final Member member) {
        return member instanceof AnnotatedElement annotated
            && annotated.isAnnotationPresent(EldApi.class);
    }

    public static boolean property(final Method method) {
        final @Nullable EldApi api = method.getAnnotation(EldApi.class);
        return api != null && api.property();
    }

    public static @Nullable String generatedMethodSignature(
        final Method method
    ) {
        final @Nullable EldApi api =
            method.getDeclaringClass().getDeclaredAnnotation(EldApi.class);
        if (api != null) {
            final String key =
                method.getName()
                    + MethodType
                        .methodType(
                            method.getReturnType(),
                            method.getParameterTypes()
                        )
                        .descriptorString();
            for (final String entry : api.methods()) {
                final int separator = entry.indexOf(':');
                if (entry.substring(0, separator).equals(key)) {
                    return entry.substring(separator + 1);
                }
            }
        }
        return null;
    }

    public static boolean javaMethodAllowed(
        final Class<?> owner,
        final Method method
    ) {
        final String name = method.getName();
        return !name.equals("equals") && !method.isBridge()
            && (method.getDeclaringClass() != Object.class
                || name.equals("toString"))
            && (METHODS.contains(name) || owner == Pattern.class
                || owner == Matcher.class
                || Throwable.class.isAssignableFrom(owner))
            && supportsJavaType(method.getGenericReturnType(), owner)
            && Arrays.stream(method.getGenericParameterTypes())
                .allMatch(parameter -> supportsJavaType(parameter, owner));
    }

    private static boolean supportsJavaType(
        final Type type,
        final Class<?> owner
    ) {
        if (type instanceof Class<?> cls) {
            return cls.isPrimitive() || cls == Object.class
                || cls == String.class
                || cls == CharSequence.class
                || cls == Byte.class
                || cls == Short.class
                || cls == Integer.class
                || cls == Long.class
                || cls == Float.class
                || cls == Double.class
                || cls == Boolean.class
                || cls == Character.class
                || CLASSES.containsValue(cls)
                || Throwable.class.isAssignableFrom(cls);
        }
        if (type instanceof TypeVariable<?> variable) {
            return Arrays.stream(owner.getTypeParameters())
                .anyMatch(
                    parameter -> parameter.getName().equals(variable.getName())
                );
        }
        if (type instanceof ParameterizedType parameterized) {
            return CLASSES.containsValue(parameterized.getRawType())
                && Arrays.stream(parameterized.getActualTypeArguments())
                    .allMatch(argument -> supportsJavaType(argument, owner));
        }
        if (type instanceof WildcardType wildcard) {
            final Type[] bounds =
                wildcard.getLowerBounds().length > 0
                    ? wildcard.getLowerBounds()
                    : wildcard.getUpperBounds();
            return Arrays.stream(bounds)
                .allMatch(bound -> supportsJavaType(bound, owner));
        }
        return false;
    }

    private static void javaProperty(
        final String name,
        final String javaName,
        final Class<?>... parameters
    ) {
        register(STRING, name, String.class, javaName, false, true, parameters);
    }

    private static void javaMethod(
        final String name,
        final String javaName,
        final Class<?>... parameters
    ) {
        register(
            STRING,
            name,
            String.class,
            javaName,
            false,
            false,
            parameters
        );
    }

    private static void javaMethod(
        final String name,
        final String javaName,
        final List<@Nullable String> parameterNames,
        final Class<?>... parameters
    ) {
        try {
            STRING.add(
                new Entry(
                    name,
                    String.class.getMethod(javaName, parameters),
                    false,
                    false,
                    parameterNames
                )
            );
        }
        catch (final NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void eldMethod(
        final String name,
        final Class<?>... parameters
    ) {
        register(STRING, name, EldString.class, name, true, false, parameters);
    }

    private static void register(
        final List<Entry> entries,
        final String name,
        final Class<?> owner,
        final String javaName,
        final boolean receiver,
        final boolean property,
        final Class<?>... parameters
    ) {
        try {
            entries.add(
                new Entry(
                    name,
                    owner.getMethod(javaName, parameters),
                    receiver,
                    property
                )
            );
        }
        catch (final NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private EldApiMethods() {}
}
