package com.github.andreasarvidsson.eld.parser;

import java.util.Locale;

public enum Visibility {
    PRIVATE,
    PUBLIC,
    PROTECTED;

    public String value() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
