package com.github.andreasarvidsson.eld.semantic;

public record InterfaceType(String name) implements Type {
    @Override
    public String toString() {
        return name;
    }
}
