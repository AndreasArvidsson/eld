package com.github.andreasarvidsson.eld;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.github.andreasarvidsson.eld.lexer.Lexer;
import com.github.andreasarvidsson.eld.parser.Parser;
import com.github.andreasarvidsson.eld.parser.VariableDeclaration;
import com.github.andreasarvidsson.eld.semantic.ArrayType;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzer;
import com.github.andreasarvidsson.eld.runtime.EldArray;

class PrimitiveArrayTest {
    private record ArrayExample(String literal, Object value) {
    }

    private static ArrayExample example(final RuntimeAbi.ArrayKind kind) {
        return switch (kind) {
            case BYTE -> new ArrayExample("7", (byte) 7);
            case SHORT -> new ArrayExample("7", (short) 7);
            case INT -> new ArrayExample("7", 7);
            case LONG -> new ArrayExample("7", 7L);
            case FLOAT -> new ArrayExample("7.5", 7.5f);
            case DOUBLE -> new ArrayExample("7.5", 7.5);
            case BOOLEAN -> new ArrayExample("true", true);
            case OBJECT -> throw new IllegalArgumentException(
                "Expected a primitive array"
            );
            case CHAR -> new ArrayExample("'x'", 'x');
        };
    }

    @Test
    void runtimeSpecializationsPreservePrimitiveStorageAndStrictBounds()
        throws Exception {
        for (final RuntimeAbi.ArrayKind kind : RuntimeAbi.ArrayKind.values()) {
            if (kind == RuntimeAbi.ArrayKind.OBJECT) {
                continue;
            }
            final Class<?> type = kind.runtimeClass;
            final Class<?> primitive =
                type.getMethod("get", int.class).getReturnType();
            assertTrue(primitive.isPrimitive());
            assertEquals(
                primitive,
                type.getDeclaredField("elements").getType().componentType()
            );
            final Object value = example(kind).value();
            final EldArray<?> array =
                (EldArray<?>) type.getConstructor().newInstance();
            final Method get = type.getMethod("get", int.class);
            final Method set = type.getMethod("set", int.class, primitive);
            final Method add = type.getMethod("add", primitive);
            final Method from = type.getMethod("sliceFrom", int.class);
            final Method to = type.getMethod("sliceTo", int.class);
            final Method slice = type.getMethod("slice", int.class, int.class);
            assertEquals(type, type.getMethod("copy").getReturnType());
            assertEquals(type, from.getReturnType());
            assertEquals(type, to.getReturnType());
            assertEquals(type, slice.getReturnType());
            assertEquals(0, array.size());
            assertEquals("[]", array.toString());
            assertEquals("[]", slice.invoke(array, 0, 0).toString());
            assertEquals("[]", from.invoke(array, 0).toString());
            assertEquals("[]", to.invoke(array, 0).toString());
            rejects(get, array, 0);
            rejects(set, array, -1, value);
            for (int i = 0; i < 101; i++) {
                add.invoke(array, value);
            }
            assertEquals(101, array.size());
            for (int i = 0; i < 101; i++) {
                assertEquals(value, get.invoke(array, i));
                assertEquals(value, get.invoke(array, i - 101));
            }
            set.invoke(array, -1, value);
            assertEquals(value, get.invoke(array, 100));
            final EldArray<?> copy =
                (EldArray<?>) type.getMethod("copy").invoke(array);
            assertEquals(type, copy.getClass());
            assertNotSame(array, copy);
            assertEquals(101, copy.size());
            assertEquals(array.toString(), copy.toString());
            final Object zero =
                java.lang.reflect.Array
                    .get(java.lang.reflect.Array.newInstance(primitive, 1), 0);
            set.invoke(copy, 0, zero);
            assertEquals(value, get.invoke(array, 0));
            add.invoke(copy, value);
            assertEquals(102, copy.size());
            assertEquals(101, array.size());
            assertEquals(
                "[" + value + "]",
                slice.invoke(array, -1, 101).toString()
            );
            assertEquals("[" + value + "]", from.invoke(array, -1).toString());
            assertEquals("[" + value + "]", to.invoke(array, 1).toString());
            assertEquals("[]", from.invoke(array, 101).toString());
            assertEquals("[]", to.invoke(array, -101).toString());
            final EldArray<?> middle =
                (EldArray<?>) slice.invoke(array, -2, -1);
            set.invoke(middle, 0, zero);
            assertEquals(value, get.invoke(array, -2));
            assertEquals(101, ((EldArray<?>) to.invoke(array, 101)).size());
            rejects(get, array, 101);
            rejects(set, array, 101, value);
            for (final int index : new int[] {-102, 102, Integer.MIN_VALUE,
                    Integer.MAX_VALUE}) {
                rejects(get, array, index);
                rejects(set, array, index, value);
                rejects(from, array, index);
                rejects(to, array, index);
                rejects(slice, array, index, 101);
                rejects(slice, array, 0, index);
            }
            rejects(slice, array, 2, 1);
            final Object backing =
                java.lang.reflect.Array.newInstance(primitive, 1);
            java.lang.reflect.Array.set(backing, 0, value);
            final EldArray<?> literal =
                (EldArray<?>) type.getConstructor(backing.getClass())
                    .newInstance(backing);
            assertEquals(1, literal.size());
            assertEquals(value, get.invoke(literal, 0));
        }
    }

    private static void rejects(
        final Method method,
        final Object receiver,
        final Object... args
    ) {
        final InvocationTargetException error =
            assertThrows(
                InvocationTargetException.class,
                () -> method.invoke(receiver, args)
            );
        assertInstanceOf(IndexOutOfBoundsException.class, error.getCause());
    }

    @Test
    void compilerUsesSpecializationsWithoutChangingSemanticArrayTypes()
        throws Exception {
        for (final RuntimeAbi.ArrayKind kind : RuntimeAbi.ArrayKind.values()) {
            if (kind == RuntimeAbi.ArrayKind.OBJECT) {
                continue;
            }
            final String element =
                kind == RuntimeAbi.ArrayKind.BOOLEAN
                    ? "bool"
                    : java.util.Objects.requireNonNull(kind.element).toString();
            final ArrayExample example = example(kind);
            final String literal = example.literal();
            final String source =
                """
                    var empty: [%s] = [];
                    var values: [%s] = [%s, %s, %s];
                    var first = values[0];
                    var last = values[-1];
                    var assigned = values[-1] = values[0];
                    var copy = values[:];
                    var tail = values[-2:];
                    var head = values[:2];
                    var middle = values[-2:-1];
                    var endEmpty = values[3:];
                    var full = values[:3];
                    var nested = [values];
                    var row = nested[0];
                    var nestedLast = row[-1];
                    func identity(input: [%s]) [%s] { return input; }
                    func count() i32 {
                        var result = 0;
                        for (value, index : values) {
                            if (index == 0) { continue; }
                            if (value == values[index]) { result = result + 1; }
                            if (index == 2) { break; }
                        }
                        return result;
                    }
                    func plain() i32 {
                        var result = 0;
                        for (value : values) {
                            if (value == values[0]) { result = result + 1; }
                        }
                        return result;
                    }
                    func badIndex() %s { return values[3]; }
                    func badSlice() [%s] { return values[:4]; }
                    func reversed() [%s] { return values[2:1]; }
                    """.formatted(
                    element,
                    element,
                    literal,
                    literal,
                    literal,
                    element,
                    element,
                    element,
                    element,
                    element
                );
            final var program =
                new Parser(new Lexer(source).getTokens()).parse();
            final var semantic = new SemanticAnalyzer().analyze(program);
            final var declaration =
                (VariableDeclaration) program.items().get(1);
            assertEquals(
                new ArrayType(java.util.Objects.requireNonNull(kind.element)),
                semantic.getExpressionType(declaration.initializer())
            );
            final Map<String, byte[]> classes =
                new BytecodeGenerator(program, semantic).generateClasses();
            for (final byte[] bytes : classes.values()) {
                if (kind == RuntimeAbi.ArrayKind.OBJECT) {
                    continue;
                }
                BytecodeUtil.verify(bytes);
            }
            final ClassModel node = ClassFile.of().parse(classes.get("Test"));
            boolean emptyConstructor = false;
            boolean backingConstructor = false;
            for (final var method : node.methods()) {
                final var instructions = BytecodeUtil.instructions(method);
                for (final var instruction : instructions) {
                    if (
                        instruction instanceof InvokeInstruction call
                            && call.owner().asInternalName().equals(kind.owner)
                            && call.name().stringValue().equals("<init>")
                    ) {
                        emptyConstructor |=
                            call.type().stringValue().equals("()V");
                        backingConstructor |=
                            call.type()
                                .stringValue()
                                .equals(kind.constructorDescriptor);
                    }
                    assertNotEquals(Opcode.ARRAYLENGTH, instruction.opcode());
                    if (
                        instruction instanceof TypeCheckInstruction cast
                            && cast.opcode() == Opcode.CHECKCAST
                    ) {
                        // Only reading the primitive row from a reference-backed nested array needs a cast.
                        assertEquals(kind.owner, cast.type().asInternalName());
                        final InvokeInstruction get =
                            assertInstanceOf(
                                InvokeInstruction.class,
                                instructions.get(instructions.indexOf(cast) - 1)
                            );
                        assertEquals(
                            RuntimeAbi.ArrayKind.OBJECT.owner,
                            get.owner().asInternalName()
                        );
                        assertEquals("get", get.name().stringValue());
                    }

                }
            }
            assertTrue(emptyConstructor, element);
            assertTrue(backingConstructor, element);
            final ModuleLoader loader = new ModuleLoader();
            loader.add(classes);
            final Class<?> type = loader.loadClass("Test");
            final EldArray<?> values =
                (EldArray<?>) type.getField("values").get(null);
            assertEquals(kind.runtimeClass, values.getClass());
            assertEquals(kind.runtimeClass, type.getField("empty").getType());
            assertEquals(
                0,
                ((EldArray<?>) type.getField("empty").get(null)).size()
            );
            assertEquals(example.value(), type.getField("first").get(null));
            assertEquals(example.value(), type.getField("last").get(null));
            assertEquals(
                example.value(),
                type.getField("nestedLast").get(null)
            );
            assertEquals(example.value(), type.getField("assigned").get(null));
            for (final String field : new String[] {"copy", "full"}) {
                final EldArray<?> result =
                    (EldArray<?>) type.getField(field).get(null);
                assertEquals(3, result.size());
                assertNotSame(values, result);
                assertEquals(values.toString(), result.toString());
            }
            assertEquals(
                2,
                ((EldArray<?>) type.getField("tail").get(null)).size()
            );
            assertEquals(
                2,
                ((EldArray<?>) type.getField("head").get(null)).size()
            );
            assertEquals(
                1,
                ((EldArray<?>) type.getField("middle").get(null)).size()
            );
            assertEquals(
                0,
                ((EldArray<?>) type.getField("endEmpty").get(null)).size()
            );
            assertSame(
                values,
                type.getMethod("identity", kind.runtimeClass)
                    .invoke(null, values)
            );
            assertEquals(2, type.getMethod("count").invoke(null));
            assertEquals(3, type.getMethod("plain").invoke(null));
            for (final String method : new String[] {"badIndex", "badSlice",
                    "reversed"}) {
                final InvocationTargetException error =
                    assertThrows(
                        InvocationTargetException.class,
                        () -> type.getMethod(method).invoke(null)
                    );
                assertInstanceOf(
                    IndexOutOfBoundsException.class,
                    error.getCause()
                );
            }
            final Class<?> primitive =
                kind.runtimeClass.getMethod("get", int.class).getReturnType();
            kind.runtimeClass.getMethod("add", primitive)
                .invoke(values, example.value());
            assertEquals(4, type.getMethod("plain").invoke(null));
        }
    }
}
