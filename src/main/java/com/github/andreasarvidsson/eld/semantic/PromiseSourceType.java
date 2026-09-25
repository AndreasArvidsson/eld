package com.github.andreasarvidsson.eld.semantic;

public record PromiseSourceType(Type valueType) implements Type {
    @Override
    public String toString() {
        return "PromiseSource<%s>".formatted(valueType);
    }
}
