package com.github.andreasarvidsson.eld.semantic;

import java.util.List;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.runtime.EldArray;

/** The members exposed by Eld arrays and their runtime implementations. */
public final class ArrayMethods {
    public static final BuiltinFunctionSymbol SORT =
        new BuiltinFunctionSymbol("sort", BuiltinFunctionType.ARRAY_SORT);

    private static final BuiltinMethodRegistry METHODS =
        new BuiltinMethodRegistry(BuiltinMethodRegistry::javaType);

    static {
        METHODS.register("length", EldArray.class, "length", false, true);
    }

    private ArrayMethods() {}

    public static List<JavaMethodSymbol> methods(
        final String name,
        final int arity,
        final Range range
    ) {
        return METHODS.methods(name, arity, range);
    }
}
