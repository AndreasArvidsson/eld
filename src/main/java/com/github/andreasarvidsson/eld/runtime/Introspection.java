package com.github.andreasarvidsson.eld.runtime;

import java.lang.reflect.Constructor;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Runtime support for Eld's {@code dir} and {@code help} builtins. */
public final class Introspection {
    private record ApiMember(
        String name, String signature, boolean property, boolean staticMember
    ) {
    }

    public static EldObjectArray<String> dir(final @Nullable Object value) {
        return dir(value, "");
    }

    public static EldObjectArray<String> dir(
        final @Nullable Object value,
        final String flattenedType
    ) {
        if (value == null) {
            return new EldObjectArray<>();
        }
        final boolean staticOnly = value instanceof Class<?>;
        final Class<?> type =
            value instanceof Class<?> represented
                ? represented
                : value.getClass();
        final String[] names =
            Stream
                .concat(
                    fields(type)
                        .filter(
                            field -> Modifier
                                .isStatic(field.getModifiers()) == staticOnly
                        )
                        .map(Member::getName),
                    members(type, flattenedType).stream()
                        .filter(member -> member.staticMember() == staticOnly)
                        .map(ApiMember::name)
                )
                .distinct()
                .sorted()
                .toArray(String[]::new);
        return new EldObjectArray<>(names);
    }

    public static void help(final @Nullable Object value) {
        System.out.println(describe(value));
    }

    public static void help(
        final @Nullable Object value,
        final String elementType,
        final String flattenedType
    ) {
        System.out.println(describe(value, elementType, flattenedType));
    }

    static String describe(final @Nullable Object value) {
        return describe(value, null, "");
    }

    private static String describe(
        final @Nullable Object value,
        final @Nullable String elementType,
        final String flattenedType
    ) {
        final @Nullable Class<?> type =
            value instanceof Class<?> represented
                ? represented
                : value != null ? value.getClass() : null;
        final StringBuilder result = new StringBuilder();
        if (type != null) {
            final List<ApiMember> members =
                members(type, flattenedType).stream()
                    .map(
                        member -> value instanceof EldArray<?>
                            ? arrayMember(
                                member,
                                elementType != null
                                    ? elementType
                                    : typeName(type).substring(
                                        1,
                                        typeName(type).length() - 1
                                    )
                            )
                            : member
                    )
                    .toList();
            appendSection(
                result,
                "Static fields",
                Stream.concat(
                    fields(type)
                        .filter(
                            field -> Modifier.isStatic(field.getModifiers())
                        )
                        .map(Introspection::signature),
                    members.stream()
                        .filter(
                            member -> member.property() && member.staticMember()
                        )
                        .map(ApiMember::signature)
                )
            );
            appendSection(
                result,
                "Static functions",
                members.stream()
                    .filter(
                        member -> !member.property() && member.staticMember()
                    )
                    .map(ApiMember::signature)
            );
            appendSection(
                result,
                "Fields",
                Stream
                    .concat(
                        fields(type)
                            .filter(
                                field -> !Modifier
                                    .isStatic(field.getModifiers())
                            )
                            .map(Introspection::signature),
                        members.stream()
                            .filter(
                                member -> member.property()
                                    && !member.staticMember()
                            )
                            .map(ApiMember::signature)
                    )
            );
            appendSection(
                result,
                "Constructors",
                Arrays.stream(type.getConstructors())
                    .filter(Introspection::visible)
                    .filter(
                        constructor -> EldEquality.EldObject.class
                            .isAssignableFrom(type)
                            || EldApiMethods.runtimeType(type)
                    )
                    .map(Introspection::signature)
            );
            appendSection(
                result,
                "Methods",
                members.stream()
                    .filter(
                        member -> !member.property() && !member.staticMember()
                    )
                    .map(ApiMember::signature)
            );
        }
        if (result.length() == 0) {
            result.append("\n | No members.");
        }
        return String.format(
            "%s%s",
            value instanceof EldArray<?> && elementType != null
                ? "[" + elementType + "]"
                : typeName(type),
            result.toString()
        );
    }

    private static ApiMember arrayMember(
        final ApiMember member,
        final String element
    ) {
        final String array = "[" + element + "]";
        final String predicate = "(value: " + element + ", index: i32) => bool";
        String signature = member.signature();
        if (signature.contains("MethodHandle")) {
            final String callback = switch (member.name()) {
                case "sort", "sortInPlace" ->
                    "(a: " + element + ", b: " + element + ") => i32";
                case "map", "flatMap" ->
                    "(value: " + element + ", index: i32) => "
                        + (member.name().equals("flatMap") ? "[any]" : "any");
                case "reduce" ->
                    "(acc: any, value: " + element + ", index: i32) => any";
                default -> predicate;
            };
            signature = signature.replace("MethodHandle", callback);
        }
        final @Nullable String result = switch (member.name()) {
            case "find", "findLast" -> element + " | null";
            case "partition" -> "(" + array + ", " + array + ")";
            case "zip" -> "[(" + element + ", any)]";
            case "removeAt" -> element;
            case "copy", "filter", "concat", "difference", "distinct",
                "intersect", "reverse", "sort", "subtract", "union" -> array;
            default -> null;
        };
        if (result != null) {
            signature =
                signature.substring(0, signature.lastIndexOf(" => ") + 4)
                    + result;
        }
        signature = switch (member.name()) {
            case "add", "addAll", "addFront", "concat", "contains",
                "difference", "index", "insertAt", "intersect", "lastIndex",
                "remove", "removeAll", "subtract", "union" ->
                signature.replace("[any]", array)
                    .replace("value: any", "value: " + element);
            default -> signature;
        };
        return new ApiMember(
            member.name(),
            signature,
            member.property(),
            member.staticMember()
        );
    }

    private static boolean visible(final Member member) {
        return EldApiMethods.runtimeVisible(member);
    }

    private static Stream<Field> fields(final Class<?> type) {
        final Stream<Field> fields =
            Arrays.stream(type.getFields())
                .filter(Introspection::visible)
                .filter(
                    field -> EldEquality.EldObject.class
                        .isAssignableFrom(field.getDeclaringClass())
                        || EldApiMethods.runtimeType(field.getDeclaringClass())
                );
        final var components = type.getRecordComponents();
        if (components != null && type.isAnnotationPresent(EldApi.class)) {
            // Eld record properties have private JVM backing fields and generated accessors.
            return Stream.concat(
                fields,
                Arrays.stream(type.getDeclaredFields())
                    .filter(
                        field -> Arrays.stream(components)
                            .anyMatch(
                                component -> component.getName()
                                    .equals(field.getName())
                            )
                    )
            );
        }
        return fields;
    }

    private static List<ApiMember> members(
        final Class<?> type,
        final String flattenedType
    ) {
        // The compiler supplies conditional availability and result types from ArrayMethods.
        return members(type).stream()
            .filter(
                member -> !EldArray.class.isAssignableFrom(type)
                    || !member.name().equals("flatten")
                    || !flattenedType.isEmpty()
            )
            .map(
                member -> EldArray.class.isAssignableFrom(type)
                    && member.name().equals("flatten")
                        ? new ApiMember(
                            member.name(),
                            "flatten() => " + flattenedType,
                            member.property(),
                            member.staticMember()
                        )
                        : member
            )
            .toList();
    }

    private static List<ApiMember> members(final Class<?> type) {
        final List<ApiMember> result = new ArrayList<>();
        if (type == String.class) {
            EldApiMethods.stringMethods()
                .forEach(entry -> result.add(member(entry)));
        }
        else if (EldApiMethods.runtimeType(type)) {
            Arrays.stream(type.getMethods())
                .filter(Introspection::visible)
                .forEach(
                    method -> result.add(
                        member(
                            new EldApiMethods.Entry(
                                method.getName(),
                                method,
                                false,
                                EldApiMethods.property(method)
                            )
                        )
                    )
                );
        }
        else if (EldEquality.EldObject.class.isAssignableFrom(type)) {
            Arrays.stream(type.getMethods())
                .filter(Introspection::visible)
                .filter(
                    method -> EldEquality.EldObject.class
                        .isAssignableFrom(method.getDeclaringClass())
                )
                .forEach(method -> {
                    final @Nullable String signature =
                        EldApiMethods.generatedMethodSignature(method);
                    if (signature != null) {
                        result
                            .add(
                                new ApiMember(
                                    signature
                                        .substring(0, signature.indexOf('(')),
                                    signature,
                                    false,
                                    Modifier.isStatic(method.getModifiers())
                                )
                            );
                    }
                });
        }
        else {
            final Class<?> owner = javaOwner(type);
            Arrays.stream(owner.getMethods())
                .filter(Introspection::visible)
                .filter(
                    method -> EldApiMethods.javaMethodAllowed(owner, method)
                )
                .forEach(
                    method -> result.add(
                        new ApiMember(
                            method.getName(),
                            signature(method),
                            false,
                            Modifier.isStatic(method.getModifiers())
                        )
                    )
                );
        }
        if (!isPrimitiveBox(type)) {
            Arrays.stream(Object.class.getMethods())
                .filter(
                    method -> EldApiMethods.OBJECT_METHODS
                        .contains(method.getName())
                )
                .filter(method -> commonMethodVisible(type, method))
                .forEach(
                    method -> result.add(
                        new ApiMember(
                            method.getName(),
                            signature(method),
                            false,
                            false
                        )
                    )
                );
        }
        return result;
    }

    private static boolean commonMethodVisible(
        final Class<?> type,
        final Method method
    ) {
        try {
            final Method implementation =
                type.getMethod(method.getName(), method.getParameterTypes());
            return !EldApiMethods
                .runtimeType(implementation.getDeclaringClass())
                || EldApiMethods.apiMember(implementation);
        }
        catch (final NoSuchMethodException ignored) {
            return false;
        }
    }

    private static boolean isPrimitiveBox(final Class<?> type) {
        return type == Byte.class || type == Short.class
            || type == Integer.class
            || type == Long.class
            || type == Float.class
            || type == Double.class
            || type == Boolean.class
            || type == Character.class;
    }

    private static Class<?> javaOwner(final Class<?> type) {
        if (
            isPrimitiveBox(type) || Throwable.class.isAssignableFrom(type)
                || EldApiMethods.CLASSES.containsValue(type)
        ) {
            return type;
        }
        return EldApiMethods.CLASSES.values()
            .stream()
            .filter(owner -> owner.isAssignableFrom(type))
            .filter(
                owner -> EldApiMethods.CLASSES.values()
                    .stream()
                    .noneMatch(
                        other -> other != owner && other.isAssignableFrom(type)
                            && owner.isAssignableFrom(other)
                    )
            )
            .sorted((left, right) -> left.getName().compareTo(right.getName()))
            .findFirst()
            .orElse(Object.class);
    }

    private static ApiMember member(final EldApiMethods.Entry entry) {
        final Method method = entry.method();
        final int offset = entry.receiverAsFirstArgument() ? 1 : 0;
        // These arguments describe the generated array representation, not Eld arguments.
        final int end = method.getParameterCount() - switch (entry.name()) {
            case "map", "flatMap", "flatten" -> 1;
            default -> 0;
        };
        final String signature =
            entry.name()
                + (entry.property()
                    ? ": "
                    : parameters(entry, offset, end) + " => ")
                + returnTypeName(method);
        return new ApiMember(
            entry.name(),
            signature,
            entry.property(),
            Modifier.isStatic(method.getModifiers())
                && !entry.receiverAsFirstArgument()
        );
    }

    private static String parameters(
        final EldApiMethods.Entry entry,
        final int offset,
        final int end
    ) {
        final List<String> parameters = new ArrayList<>();
        for (int i = offset; i < end; i++) {
            final @Nullable String name =
                entry.parameterNames().get(i - offset);
            parameters.add(
                (name == null ? "" : name + ": ")
                    + typeName(entry.method().getParameterTypes()[i])
            );
        }
        return "(" + String.join(", ", parameters) + ")";
    }

    private static String returnTypeName(final Method method) {
        if (
            method.getGenericReturnType() instanceof ParameterizedType generic
                && generic.getRawType() == EldObjectArray.class
                && generic
                    .getActualTypeArguments()[0] instanceof Class<?> element
        ) {
            return "[" + typeName(element) + "]";
        }
        return typeName(method.getReturnType());
    }

    private static void appendSection(
        final StringBuilder result,
        final String title,
        final Stream<String> entries
    ) {
        final var sorted = entries.distinct().sorted().toList();
        if (!sorted.isEmpty()) {
            if (result.length() > 0) {
                result.append("\n |");
            }
            result.append("\n | ").append(title).append(':');
            sorted.forEach(entry -> result.append("\n |   ").append(entry));
        }
    }

    private static String signature(final Constructor<?> constructor) {
        final @Nullable EldApi api =
            constructor.getDeclaringClass().getDeclaredAnnotation(EldApi.class);
        if (api != null) {
            final String descriptor =
                MethodType
                    .methodType(void.class, constructor.getParameterTypes())
                    .descriptorString();
            for (final String entry : api.constructors()) {
                final int separator = entry.indexOf(':');
                if (entry.substring(0, separator).equals(descriptor)) {
                    return entry.substring(separator + 1);
                }
            }
        }
        return "constructor" + parameters(constructor.getParameters());
    }

    private static String signature(final Field field) {
        return field.getName() + ": " + typeName(field.getType());
    }

    private static String signature(final Method method) {
        return method.getName() + parameters(method.getParameters()) + " => "
            + typeName(method.getReturnType());
    }

    private static String parameters(final Parameter[] parameters) {
        return Arrays.stream(parameters)
            .map(
                parameter -> (parameter.isNamePresent()
                    ? parameter.getName() + ": "
                    : "") + typeName(parameter.getType())
            )
            .reduce((left, right) -> left + ", " + right)
            .map(value -> "(" + value + ")")
            .orElse("()");
    }

    private static String typeName(final @Nullable Class<?> type) {
        if (type == null) {
            return "null";
        }
        if (type.isArray()) {
            return "[" + typeName(type.getComponentType()) + "]";
        }
        final @Nullable String builtin = switch (type.getName()) {
            case "byte", "java.lang.Byte" -> "i8";
            case "short", "java.lang.Short" -> "i16";
            case "int", "java.lang.Integer" -> "i32";
            case "long", "java.lang.Long" -> "i64";
            case "float", "java.lang.Float" -> "f32";
            case "double", "java.lang.Double" -> "f64";
            case "boolean", "java.lang.Boolean" -> "bool";
            case "char", "java.lang.Character" -> "char";
            case "java.lang.String", "java.lang.CharSequence" -> "string";
            case "java.lang.Object" -> "any";
            case "java.lang.Class" -> "Type";
            case "void", "java.lang.Void" -> "void";
            default -> null;
        };
        if (builtin != null) {
            return builtin;
        }
        if (EldArray.class.isAssignableFrom(type)) {
            if (type == EldByteArray.class) {
                return "[i8]";
            }
            if (type == EldShortArray.class) {
                return "[i16]";
            }
            if (type == EldIntArray.class) {
                return "[i32]";
            }
            if (type == EldLongArray.class) {
                return "[i64]";
            }
            if (type == EldFloatArray.class) {
                return "[f32]";
            }
            if (type == EldDoubleArray.class) {
                return "[f64]";
            }
            if (type == EldBooleanArray.class) {
                return "[bool]";
            }
            if (type == EldCharArray.class) {
                return "[char]";
            }
            return "[any]";
        }
        if (type == EldPromise.class) {
            return "Promise<any>";
        }
        for (final var entry : EldApiMethods.CLASSES.entrySet()) {
            if (entry.getValue() == type) {
                return entry.getKey();
            }
        }
        final String simple = type.getSimpleName();
        return simple.isEmpty() ? type.getTypeName() : simple;
    }

    private Introspection() {}
}
