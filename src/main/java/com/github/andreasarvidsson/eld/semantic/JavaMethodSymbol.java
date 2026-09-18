package com.github.andreasarvidsson.eld.semantic;

import java.lang.reflect.Method;
import com.github.andreasarvidsson.eld.Range;

public record JavaMethodSymbol(Method method, FunctionType type, Range range)
    implements Symbol {

    @Override
    public String name() {
        return method.getName();
    }

}
