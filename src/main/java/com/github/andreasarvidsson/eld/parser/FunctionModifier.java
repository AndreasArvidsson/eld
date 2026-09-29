package com.github.andreasarvidsson.eld.parser;

import java.util.Locale;

public enum FunctionModifier {
    ABSTRACT,
    FINAL,
    CONST,
    ASYNC,
    OVERRIDE;

    public String value() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
