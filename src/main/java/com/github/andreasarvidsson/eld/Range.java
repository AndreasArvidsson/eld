package com.github.andreasarvidsson.eld;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

public record Range(Position start, Position end)
    implements Comparable<@NonNull Range> {

    public Range(
        final int startLine,
        final int startColumn,
        final int endLine,
        final int endColumn
    ) {
        this(
            new Position(startLine, startColumn),
            new Position(endLine, endColumn)
        );
    }

    public Range union(final Range other) {
        return new Range(
            this.start().isBefore(other.start()) ? this.start() : other.start(),
            this.end().isAfter(other.end()) ? this.end() : other.end()
        );
    }

    @Override
    public String toString() {
        return Objects.requireNonNull(String.format("%s-%s", start(), end()));
    }

    @Override
    public int compareTo(final Range other) {
        final int cmp = this.start().compareTo(other.start());
        if (cmp != 0) {
            return cmp;
        }
        return this.end().compareTo(other.end());
    }

}
