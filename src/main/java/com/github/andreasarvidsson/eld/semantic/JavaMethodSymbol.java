package com.github.andreasarvidsson.eld.semantic;

import java.lang.reflect.Method;
import com.github.andreasarvidsson.eld.Range;

public record JavaMethodSymbol(
    Method method, FunctionType type, Range range,
    boolean receiverAsFirstArgument, boolean property
) implements Symbol {

    public JavaMethodSymbol(
        final Method method,
        final FunctionType type,
        final Range range
    ) {
        this(method, type, range, false, false);
    }

    @Override
    public String name() {
        return method.getName();
    }

}
