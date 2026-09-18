package com.github.andreasarvidsson.eld.semantic;

public record ClassType(String name) implements Type {

    @Override
    public String toString() {
        return name;
    }
}
