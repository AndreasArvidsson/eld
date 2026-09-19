package com.github.andreasarvidsson.eld.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Runtime support for Eld's {@code dir} and {@code help} builtins. */
public final class Introspection {
    public static EldObjectArray<String> dir(final @Nullable Object value) {
        if (value == null) {
            return new EldObjectArray<>();
        }
        final String[] names =
            Stream
                .concat(
                    Arrays.stream(value.getClass().getFields())
                        .filter(Introspection::visible),
                    Arrays.stream(value.getClass().getMethods())
                        .filter(Introspection::visible)
                )
                .map(Member::getName)
                .distinct()
                .sorted()
                .toArray(String[]::new);
        return new EldObjectArray<>(names);
    }

    public static void help(final @Nullable Object value) {
        System.out.println(describe(value));
    }

    static String describe(final @Nullable Object value) {
        if (value == null) {
            return "null\nNo members.";
        }
        final Class<?> type = value.getClass();
        final StringBuilder result = new StringBuilder(typeName(type));
        appendSection(
            result,
            "Constructors",
            Arrays.stream(type.getConstructors())
                .filter(Introspection::visible)
                .map(Introspection::signature)
        );
        appendSection(
            result,
            "Fields",
            Arrays.stream(type.getFields())
                .filter(Introspection::visible)
                .map(Introspection::signature)
        );
        appendSection(
            result,
            "Methods",
            Arrays.stream(type.getMethods())
                .filter(Introspection::visible)
                .map(Introspection::signature)
        );
        if (result.indexOf("\n") < 0) {
            result.append("\nNo members.");
        }
        return result.toString();
    }

    private static boolean visible(final Member member) {
        return Modifier.isPublic(member.getModifiers()) && !member.isSynthetic()
            && !member.getName().startsWith("$");
    }

    private static void appendSection(
        final StringBuilder result,
        final String title,
        final Stream<String> entries
    ) {
        final var sorted = entries.distinct().sorted().toList();
        if (!sorted.isEmpty()) {
            result.append('\n').append(title).append(':');
            sorted.forEach(entry -> result.append("\n  ").append(entry));
        }
    }

    private static String signature(final Constructor<?> constructor) {
        return typeName(constructor.getDeclaringClass())
            + parameters(constructor.getParameterTypes());
    }

    private static String signature(final Field field) {
        return typeName(field.getType()) + " " + field.getName();
    }

    private static String signature(final Method method) {
        return typeName(method.getReturnType()) + " " + method.getName()
            + parameters(method.getParameterTypes());
    }

    private static String parameters(final Class<?>[] parameters) {
        return Arrays.stream(parameters)
            .map(Introspection::typeName)
            .reduce((left, right) -> left + ", " + right)
            .map(value -> "(" + value + ")")
            .orElse("()");
    }

    private static String typeName(final Class<?> type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        final String simple = type.getSimpleName();
        return simple.isEmpty() ? type.getTypeName() : simple;
    }

    private Introspection() {}
}
