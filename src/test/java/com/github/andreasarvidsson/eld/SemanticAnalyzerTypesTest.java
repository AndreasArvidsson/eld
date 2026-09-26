package com.github.andreasarvidsson.eld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.semantic.ArrayType;
import com.github.andreasarvidsson.eld.semantic.BuiltinType;
import com.github.andreasarvidsson.eld.semantic.ClassType;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzerTypes;
import com.github.andreasarvidsson.eld.semantic.SemanticModel;
import com.github.andreasarvidsson.eld.semantic.TupleType;
import com.github.andreasarvidsson.eld.semantic.Type;
import com.github.andreasarvidsson.eld.semantic.UnionType;

class SemanticAnalyzerTypesTest {
    private record Fixture(List<Type> input, @Nullable String expected) {
    }

    @Test
    void commonType() {
        final SemanticModel model = new SemanticModel();
        final SemanticAnalyzerTypes analyzer = new SemanticAnalyzerTypes(model);
        final ClassType animal = new ClassType("Animal");
        final ClassType dog = new ClassType("Dog");
        final ClassType cat = new ClassType("Cat");
        model.setSuperclass(dog, animal);
        model.setSuperclass(cat, animal);

        final List<Fixture> fixtures =
            List.of(
                new Fixture(List.of(), null),
                new Fixture(List.of(BuiltinType.NULL), "null"),
                new Fixture(List.of(BuiltinType.VOID), "void"),
                new Fixture(List.of(BuiltinType.I32), "i32"),
                new Fixture(List.of(BuiltinType.I64), "i64"),
                new Fixture(List.of(BuiltinType.F32), "f32"),
                new Fixture(List.of(BuiltinType.F64), "f64"),
                new Fixture(List.of(BuiltinType.I32, BuiltinType.I32), "i32"),
                new Fixture(List.of(BuiltinType.F32, BuiltinType.F32), "f32"),
                new Fixture(List.of(BuiltinType.I8, BuiltinType.I16), "i16"),
                new Fixture(
                    List.of(BuiltinType.I8, BuiltinType.I32, BuiltinType.I64),
                    "i64"
                ),
                new Fixture(List.of(BuiltinType.F32, BuiltinType.F64), "f64"),
                new Fixture(
                    List.of(BuiltinType.I32, BuiltinType.F64),
                    "i32 | f64"
                ),
                new Fixture(
                    List.of(BuiltinType.I8, BuiltinType.F64, BuiltinType.I32),
                    "i32 | f64"
                ),
                new Fixture(
                    List.of(BuiltinType.BOOL, BuiltinType.STRING),
                    "bool | string"
                ),
                new Fixture(
                    List.of(BuiltinType.STRING, BuiltinType.NULL),
                    "string | null"
                ),
                new Fixture(
                    List.of(BuiltinType.I8, BuiltinType.I16, BuiltinType.NULL),
                    "i16 | null"
                ),
                new Fixture(
                    List.of(BuiltinType.I32, BuiltinType.F64, BuiltinType.NULL),
                    "i32 | f64 | null"
                ),
                new Fixture(List.of(BuiltinType.I32, BuiltinType.ANY), "any"),
                new Fixture(List.of(BuiltinType.VOID, BuiltinType.I32), null),
                new Fixture(List.of(BuiltinType.VOID, BuiltinType.ANY), null),
                new Fixture(
                    List.of(
                        UnionType.of(List.of(BuiltinType.I32, BuiltinType.I32))
                    ),
                    "i32"
                ),
                new Fixture(
                    List.of(
                        UnionType
                            .of(List.of(BuiltinType.I8, BuiltinType.STRING)),
                        BuiltinType.I32
                    ),
                    "i8 | string | i32"
                ),
                new Fixture(
                    List.of(
                        new ArrayType(BuiltinType.I32),
                        new ArrayType(BuiltinType.STRING)
                    ),
                    "[i32] | [string]"
                ),
                new Fixture(
                    List.of(
                        new TupleType(List.of(BuiltinType.I32)),
                        new TupleType(List.of(BuiltinType.STRING))
                    ),
                    "(i32) | (string)"
                ),
                new Fixture(List.of(dog, animal), "Animal"),
                new Fixture(List.of(dog, cat), "Animal"),
                new Fixture(List.of(dog, BuiltinType.STRING), "Dog | string"),
                new Fixture(
                    List.of(UnionType.of(List.of(dog, cat)), animal),
                    "Dog | Cat | Animal"
                ),
                new Fixture(
                    List.of(dog, animal, BuiltinType.STRING),
                    "Animal | string"
                )
            );

        for (final Fixture fixture : fixtures) {
            final @Nullable Type actual = analyzer.commonType(fixture.input());
            assertEquals(
                fixture.expected(),
                actual == null ? null : actual.toString(),
                fixture.input().toString()
            );
            if (fixture.input().size() > 1) {
                final List<Type> reversed = new ArrayList<>(fixture.input());
                Collections.reverse(reversed);
                assertEquals(
                    actual,
                    analyzer.commonType(reversed),
                    fixture.input().toString()
                );
            }
        }
    }

    @Test
    void commonTypeStrictKeepsCollectionInference() {
        final SemanticAnalyzerTypes analyzer =
            new SemanticAnalyzerTypes(new SemanticModel());
        assertNull(
            analyzer.commonTypeStrict(List.of(BuiltinType.I32, BuiltinType.F64))
        );
        assertEquals(
            BuiltinType.I16,
            analyzer.commonTypeStrict(List.of(BuiltinType.I8, BuiltinType.I16))
        );
    }
}
