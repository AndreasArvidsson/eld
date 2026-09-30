package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.runtime.EldByteArray;
import com.github.andreasarvidsson.eld.runtime.EldCharArray;
import com.github.andreasarvidsson.eld.runtime.EldObjectArray;
import com.github.andreasarvidsson.eld.runtime.EldString;

/** The methods exposed by Eld strings and their Java implementations. */
public final class StringMethods {
    private static final BuiltinMethodRegistry METHODS =
        new BuiltinMethodRegistry(StringMethods::returnType);

    static {
        javaProperty("length", "length");
        javaMethod("isEmpty", "isEmpty");
        javaMethod("isBlank", "isBlank");
        javaMethod("startsWith", "startsWith", String.class);
        javaMethod("endsWith", "endsWith", String.class);
        javaMethod("contains", "contains", CharSequence.class);
        javaMethod("compare", "compareTo", String.class);
        javaMethod("compareIgnoreCase", "compareToIgnoreCase", String.class);
        javaMethod("equalsIgnoreCase", "equalsIgnoreCase", String.class);
        javaMethod("hashCode", "hashCode");
        javaMethod("upper", "toUpperCase");
        javaMethod("lower", "toLowerCase");
        javaMethod("strip", "strip");
        javaMethod("stripStart", "stripLeading");
        javaMethod("stripEnd", "stripTrailing");
        javaMethod(
            "replaceAll",
            "replace",
            CharSequence.class,
            CharSequence.class
        );
        javaMethod("repeat", "repeat", int.class);

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

    private StringMethods() {}

    private static void javaProperty(
        final String eldName,
        final String javaName,
        final Class<?>... parameters
    ) {
        METHODS
            .register(eldName, String.class, javaName, false, true, parameters);
    }

    private static void javaMethod(
        final String eldName,
        final String javaName,
        final Class<?>... parameters
    ) {
        METHODS.register(
            eldName,
            String.class,
            javaName,
            false,
            false,
            parameters
        );
    }

    private static void eldMethod(
        final String name,
        final Class<?>... parameters
    ) {
        METHODS.register(name, EldString.class, name, true, false, parameters);
    }

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
