package com.github.andreasarvidsson.eld.semantic;

/** A type parameter used in the displayed signature of a special method. */
public record TypeParameterType(String name) implements Type {
    @Override
    public String toString() {
        return name;
    }
}
