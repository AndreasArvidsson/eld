package com.github.andreasarvidsson.eld.semantic;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public record UnionType(List<Type> memberTypes) implements Type {

    public static Type of(final List<Type> members) {
        if (
            members.stream()
                .anyMatch(
                    member -> member == BuiltinType.ANY
                        || (member instanceof UnionType nested
                            && nested.contains(BuiltinType.ANY))
                )
        ) {
            return BuiltinType.ANY;
        }
        final UnionType union = new UnionType(members);
        return union.memberTypes.size() == 1
            ? union.memberTypes.getFirst()
            : union;
    }

    public boolean contains(final Type type) {
        return type instanceof UnionType union
            ? memberTypes.containsAll(union.memberTypes)
            : memberTypes.contains(type);
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return other instanceof UnionType union && new HashSet<>(memberTypes)
            .equals(new HashSet<>(union.memberTypes));
    }

    @Override
    public int hashCode() {
        return new HashSet<>(memberTypes).hashCode();
    }

    @Override
    public String toString() {
        return memberTypes.stream()
            .map(Object::toString)
            .collect(Collectors.joining(" | "));
    }
}
