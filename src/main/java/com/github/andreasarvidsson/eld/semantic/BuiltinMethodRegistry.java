package com.github.andreasarvidsson.eld.semantic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.github.andreasarvidsson.eld.Range;

/** Explicit Java method mappings shared by built-in Eld types. */
final class BuiltinMethodRegistry {
    private record Entry(
        Method method, boolean receiverAsFirstArgument, boolean property
    ) {
    }

    private static final InterfaceType OBJECT =
        new InterfaceType("any", List.of(), Object.class);

    private final Map<String, List<Entry>> methods = new HashMap<>();
    private final Function<Class<?>, Type> returnType;

    BuiltinMethodRegistry(final Function<Class<?>, Type> returnType) {
        this.returnType = returnType;
    }

    void register(
        final String eldName,
        final Class<?> owner,
        final String javaName,
        final boolean receiverAsFirstArgument,
        final boolean property,
        final Class<?>... parameters
    ) {
        try {
            methods.computeIfAbsent(eldName, ignored -> new ArrayList<>())
                .add(
                    new Entry(
                        owner.getMethod(javaName, parameters),
                        receiverAsFirstArgument,
                        property
                    )
                );
        }
        catch (final NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    List<JavaMethodSymbol> methods(
        final String name,
        final int arity,
        final Range range
    ) {
        final List<JavaMethodSymbol> candidates = new ArrayList<>();
        for (final Entry entry : methods.getOrDefault(name, List.of())) {
            final Method method = entry.method();
            final int offset = entry.receiverAsFirstArgument() ? 1 : 0;
            if (arity >= 0 && method.getParameterCount() - offset != arity) {
                continue;
            }
            final List<Type> parameters =
                Arrays
                    .stream(
                        method.getParameterTypes(),
                        offset,
                        method.getParameterCount()
                    )
                    .map(parameter -> JavaTypes.resolve(parameter, OBJECT))
                    .toList();
            candidates.add(
                new JavaMethodSymbol(
                    method,
                    new FunctionType(
                        parameters,
                        returnType.apply(method.getReturnType())
                    ),
                    range,
                    entry.receiverAsFirstArgument(),
                    entry.property()
                )
            );
        }
        return candidates;
    }

    static Type javaType(final Class<?> type) {
        return JavaTypes.resolve(type, OBJECT);
    }
}
