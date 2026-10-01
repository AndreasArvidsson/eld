package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.runtime.EldByteArray;
import com.github.andreasarvidsson.eld.runtime.EldCharArray;
import com.github.andreasarvidsson.eld.runtime.EldObjectArray;
import com.github.andreasarvidsson.eld.runtime.EldApiMethods;

/** The methods exposed by Eld strings and their Java implementations. */
public final class StringMethods {
    private static final BuiltinMethodRegistry METHODS =
        new BuiltinMethodRegistry(StringMethods::returnType);

    static {
        for (final EldApiMethods.Entry entry : EldApiMethods.stringMethods()) {
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

    private StringMethods() {}
    public static List<JavaMethodSymbol> methods(
        final String name,
        final int arity,
        final Range range
    ) {
        return METHODS.methods(name, arity, range);
    }

    private static Type returnType(final Class<?> returned) {
        if (returned == Integer.class) {
            return UnionType.of(List.of(BuiltinType.I32, BuiltinType.NULL));
        }
        if (returned == EldByteArray.class) {
            return new ArrayType(BuiltinType.I8);
        }
        if (returned == EldCharArray.class) {
            return new ArrayType(BuiltinType.CHAR);
        }
        if (returned == EldObjectArray.class) {
            return new ArrayType(BuiltinType.STRING);
        }
        return BuiltinMethodRegistry.javaType(returned);
    }
}
