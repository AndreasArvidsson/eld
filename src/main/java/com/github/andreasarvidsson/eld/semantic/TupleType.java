package com.github.andreasarvidsson.eld.semantic;

import java.util.List;

public record TupleType(List<Type> elementTypes) implements Type {
}
