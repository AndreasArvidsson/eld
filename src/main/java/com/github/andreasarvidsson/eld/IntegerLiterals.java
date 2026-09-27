package com.github.andreasarvidsson.eld;

import java.math.BigInteger;

public final class IntegerLiterals {
    private IntegerLiterals() {}

    public static BigInteger parse(final String text) {
        final String digits = text.replace("_", "");
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            return new BigInteger(digits.substring(2), 16);
        }
        if (digits.startsWith("0b") || digits.startsWith("0B")) {
            return new BigInteger(digits.substring(2), 2);
        }
        return new BigInteger(digits);
    }
}
