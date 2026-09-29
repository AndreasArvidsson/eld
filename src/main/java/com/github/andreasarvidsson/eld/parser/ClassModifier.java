package com.github.andreasarvidsson.eld.parser;

import java.util.Locale;

public enum ClassModifier {
    ABSTRACT;

    public String value() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
