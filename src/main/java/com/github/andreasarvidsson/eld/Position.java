package com.github.andreasarvidsson.eld;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

public record Position(int line, int column)
    implements Comparable<@NonNull Position> {

    /**
    * Check if this position is before `other`.
    *
    * @param other A position.
    * @return `true` if position is on a smaller line
    * or on the same line on a smaller character.
    */
    public boolean isBefore(final Position other) {
        if (this.line() < other.line()) {
            return true;
        }
        if (this.line() > other.line()) {
            return false;
        }
        return this.column() < other.column();
    }

    /**
     * Check if this position is after `other`.
     *
     * @param other A position.
     * @return `true` if position is on a greater line
     * or on the same line on a greater character.
     */
    public boolean isAfter(final Position other) {
        if (this.line() > other.line()) {
            return true;
        }
        if (this.line() < other.line()) {
            return false;
        }
        return this.column() > other.column();
    }

    @Override
    public int compareTo(final Position other) {
        if (this.line() != other.line()) {
            return this.line() - other.line();
        }
        return this.column() - other.column();
    }

    @Override
    public String toString() {
        return Objects.requireNonNull(String.format("%d:%d", line(), column()));
    }
}
