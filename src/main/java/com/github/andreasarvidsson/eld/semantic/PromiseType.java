package com.github.andreasarvidsson.eld.semantic;

public record PromiseType(Type valueType) implements Type {
    @Override
    public String toString() {
        return "Promise<%s>".formatted(valueType);
    }
}
