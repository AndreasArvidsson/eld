package com.github.andreasarvidsson.eld;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.*;
import java.lang.reflect.InvocationTargetException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import com.github.andreasarvidsson.eld.lexer.Lexer;
import com.github.andreasarvidsson.eld.parser.Parser;
import com.github.andreasarvidsson.eld.parser.VariableDeclaration;
import com.github.andreasarvidsson.eld.runtime.EldObjectArray;
import com.github.andreasarvidsson.eld.semantic.ArrayType;
import com.github.andreasarvidsson.eld.semantic.BuiltinType;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzer;

class ObjectArrayTest {
    @Test
    void growsWithNullableReferencesAndUsesLogicalBounds() throws Exception {
        final EldObjectArray<@Nullable String> values = new EldObjectArray<>();
        assertEquals("[]", values.toString());
        assertEquals(0, values.slice(0, 0).size());
        assertThrows(IndexOutOfBoundsException.class, () -> values.get(0));
        for (int i = 0; i < 101; i++) {
            values.add(i % 2 == 0 ? "value" : null);
        }
        assertEquals(101, values.size());
        for (int i = 0; i < 101; i++) {
            assertEquals(i % 2 == 0 ? "value" : null, values.get(i - 101));
        }
        values.set(-1, null);
        assertNull(values.get(100));
        assertEquals("[null, null]", values.slice(-2, 101).toString());
        assertEquals(0, values.sliceFrom(101).size());
        assertEquals(0, values.sliceTo(-101).size());
        assertEquals(100, values.sliceTo(-1).size());
        for (final int index : new int[] {-102, 102, Integer.MIN_VALUE,
                Integer.MAX_VALUE}) {
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> values.get(index)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> values.set(index, null)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> values.sliceFrom(index)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> values.sliceTo(index)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> values.slice(index, 101)
            );
            assertThrows(
                IndexOutOfBoundsException.class,
                () -> values.slice(0, index)
            );
        }
        assertThrows(IndexOutOfBoundsException.class, () -> values.get(101));
        assertThrows(
            IndexOutOfBoundsException.class,
            () -> values.set(101, "bad")
        );
        assertThrows(IndexOutOfBoundsException.class, () -> values.slice(2, 1));
        for (final EldObjectArray<@Nullable String> copy : java.util.List.of(
            values.copy(),
            values.sliceFrom(0),
            values.sliceTo(101),
            values.slice(0, 101)
        )) {
            assertNotSame(values, copy);
            copy.set(0, "changed");
            copy.add("extra");
            assertEquals("value", values.get(0));
            assertEquals(101, values.size());
        }
        assertEquals(
            Object[].class,
            EldObjectArray.class.getDeclaredField("elements").getType()
        );
        assertEquals(
            Object.class,
            EldObjectArray.class.getMethod("get", int.class).getReturnType()
        );
        assertEquals(
            EldObjectArray.class,
            EldObjectArray.class.getMethod("copy").getReturnType()
        );
        assertEquals(
            EldObjectArray.class,
            EldObjectArray.class.getMethod("sliceFrom", int.class)
                .getReturnType()
        );
        assertEquals(
            EldObjectArray.class,
            EldObjectArray.class.getMethod("sliceTo", int.class).getReturnType()
        );
        assertEquals(
            EldObjectArray.class,
            EldObjectArray.class.getMethod("slice", int.class, int.class)
                .getReturnType()
        );
    }

    @Test
    void compilerPreservesReferenceElementTypesAndUsesRuntimeOperations()
        throws Exception {
        for (final BuiltinType element : new BuiltinType[] {BuiltinType.STRING,
                BuiltinType.NULL}) {
            final String literal =
                element == BuiltinType.STRING
                    ? "\"a\", \"b\", \"c\""
                    : "null, null, null";
            final String source =
                """
                    var empty: [%s] = [];
                    var values: [%s] = [%s];
                    var first = values[0];
                    var last = values[-1];
                    var copy = values[:];
                    var tail = values[1:];
                    var head = values[:2];
                    var middle = values[1:2];
                    var negativeHead = values[:-1];
                    var negativeTail = values[-2:];
                    var negativeMiddle = values[-2:-1];
                    var assigned = values[-1] = values[0];
                    var nested = [values];
                    var row = nested[0];
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
                            var typed: %s = value;
                            result = result + 1;
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
                    element,
                    element,
                    element,
                    element,
                    element,
                    element
                );
            final var program =
                new Parser(new Lexer(source).getTokens()).parse();
            final var semantic = new SemanticAnalyzer().analyze(program);
            for (final int index : new int[] {0, 1, 4, 5, 6, 7, 8, 9, 10}) {
                final var declaration =
                    (VariableDeclaration) program.items().get(index);
                assertEquals(
                    new ArrayType(element),
                    semantic.getExpressionType(declaration.initializer())
                );
            }
            for (final int index : new int[] {2, 3, 11}) {
                final var declaration =
                    (VariableDeclaration) program.items().get(index);
                assertEquals(
                    element,
                    semantic.getExpressionType(declaration.initializer())
                );
            }
            final var classes =
                new BytecodeGenerator(program, semantic).generateClasses();
            for (final byte[] bytes : classes.values()) {
                BytecodeUtil.verify(bytes);
            }
            final ClassModel node = ClassFile.of().parse(classes.get("Test"));
            boolean emptyConstructor = false;
            boolean backingConstructor = false;
            boolean elementCast = false;
            for (final var method : node.methods()) {
                final var instructions = BytecodeUtil.instructions(method);
                for (final var instruction : instructions) {
                    assertNotEquals(Opcode.ARRAYLENGTH, instruction.opcode());
                    assertNotEquals(Opcode.AALOAD, instruction.opcode());
                    if (
                        instruction instanceof InvokeInstruction call
                            && call.owner()
                                .asInternalName()
                                .equals(RuntimeAbi.ArrayKind.OBJECT.owner)
                    ) {
                        if (call.name().stringValue().equals("<init>")) {
                            emptyConstructor |=
                                call.type().stringValue().equals("()V");
                            backingConstructor |=
                                call.type()
                                    .stringValue()
                                    .equals("([Ljava/lang/Object;)V");
                        }
                        if (
                            call.name().stringValue().equals("copy")
                                || call.name().stringValue().startsWith("slice")
                        ) {
                            assertTrue(
                                call.type()
                                    .stringValue()
                                    .endsWith(
                                        RuntimeAbi.ArrayKind.OBJECT.descriptor
                                    )
                            );
                            assertNotEquals(
                                Opcode.CHECKCAST,
                                BytecodeUtil.instructions(method)
                                    .get(
                                        BytecodeUtil.instructions(method)
                                            .indexOf(instruction) + 1
                                    )
                                    .opcode()
                            );
                        }
                    }
                    if (
                        instruction instanceof TypeCheckInstruction cast
                            && cast.opcode() == Opcode.CHECKCAST
                    ) {
                        elementCast |=
                            cast.type()
                                .asInternalName()
                                .equals("java/lang/String");
                    }
                }
            }
            assertTrue(emptyConstructor);
            assertTrue(backingConstructor);
            assertEquals(element == BuiltinType.STRING, elementCast);
            final ModuleLoader loader = new ModuleLoader();
            loader.add(classes);
            final Class<?> type = loader.loadClass("Test");
            final EldObjectArray<?> values =
                (EldObjectArray<?>) type.getField("values").get(null);
            assertEquals(
                EldObjectArray.class,
                type.getField("empty").getType()
            );
            assertEquals(
                0,
                ((EldObjectArray<?>) type.getField("empty").get(null)).size()
            );
            assertEquals(
                element == BuiltinType.STRING ? "a" : null,
                type.getField("first").get(null)
            );
            assertEquals(
                element == BuiltinType.STRING ? "c" : null,
                type.getField("last").get(null)
            );
            assertEquals(values.get(0), type.getField("assigned").get(null));
            assertSame(values, type.getField("row").get(null));
            assertSame(
                values,
                type.getMethod("identity", EldObjectArray.class)
                    .invoke(null, values)
            );
            final EldObjectArray<?> copy =
                (EldObjectArray<?>) type.getField("copy").get(null);
            assertNotSame(values, copy);
            assertEquals(
                element == BuiltinType.STRING
                    ? "[a, b, c]"
                    : "[null, null, null]",
                copy.toString()
            );
            for (final String name : new String[] {"tail", "head",
                    "negativeHead", "negativeTail"}) {
                assertEquals(
                    2,
                    ((EldObjectArray<?>) type.getField(name).get(null)).size()
                );
            }
            for (final String name : new String[] {"middle",
                    "negativeMiddle"}) {
                assertEquals(
                    element == BuiltinType.STRING ? "[b]" : "[null]",
                    type.getField(name).get(null).toString()
                );
            }
            assertEquals(2, type.getMethod("count").invoke(null));
            assertEquals(3, type.getMethod("plain").invoke(null));
            for (final String name : new String[] {"badIndex", "badSlice",
                    "reversed"}) {
                final InvocationTargetException error =
                    assertThrows(
                        InvocationTargetException.class,
                        () -> type.getMethod(name).invoke(null)
                    );
                assertInstanceOf(
                    IndexOutOfBoundsException.class,
                    error.getCause()
                );
            }
        }
    }
}
