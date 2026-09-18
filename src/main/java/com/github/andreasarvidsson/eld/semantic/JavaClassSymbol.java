package com.github.andreasarvidsson.eld.semantic;

import com.github.andreasarvidsson.eld.Range;

public record JavaClassSymbol(InterfaceType type, Range range)
    implements Symbol {

    @Override
    public String name() {
        return type.name();
    }

}
