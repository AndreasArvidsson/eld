package com.github.andreasarvidsson.eld;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;
import com.github.andreasarvidsson.eld.lexer.Lexer;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.AssignmentExpression;
import com.github.andreasarvidsson.eld.parser.ExpressionStatement;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.SubscriptExpression;
import com.github.andreasarvidsson.eld.parser.LiteralExpression;
import com.github.andreasarvidsson.eld.parser.LiteralKind;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.Parser;
import com.github.andreasarvidsson.eld.parser.PostfixExpression;
import com.github.andreasarvidsson.eld.parser.PostfixOperator;
import com.github.andreasarvidsson.eld.parser.Program;
import com.github.andreasarvidsson.eld.parser.UnaryExpression;
import com.github.andreasarvidsson.eld.parser.UnaryOperator;
import com.github.andreasarvidsson.eld.parser.VariableDeclaration;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzer;
import com.github.andreasarvidsson.eld.semantic.SemanticException;
import com.github.andreasarvidsson.eld.runtime.EldIntArray;
import com.github.andreasarvidsson.eld.runtime.EldObjectArray;
import com.github.andreasarvidsson.eld.runtime.EldArray;
import com.github.andreasarvidsson.eld.runtime.EldLongArray;
import com.github.andreasarvidsson.eld.runtime.EldDoubleArray;
import com.github.andreasarvidsson.eld.runtime.EldCharArray;
import com.github.andreasarvidsson.eld.runtime.EldBooleanArray;

class BytecodeGeneratorTest {
    @Test
    void namedArgumentsBindByNameAndEvaluateInSourceOrder() throws Exception {
        final Class<?> type = compile("""
            func isLess(a: i32, b: i32) bool { return a < b; }
            func positional() bool { return isLess(5, 2); }
            func named() bool { return isLess(b=5, a=2); }
            func mixed() bool { return isLess(2, b=5); }
            func grouped() bool { return (isLess)(b=5, a=2); }
            func combine(a: i64, b: f64) f64 { return a * 10 + b; }
            func converted() f64 { return combine(b=2, a=3); }
            func next(value: i32) i32 { print(value); return value; }
            func pair(a: i32, b: i32) i32 { return a * 10 + b; }
            func ordered() i32 {
                return pair(b=next(1), a=next(2));
            }
            """);
        assertEquals(false, type.getMethod("positional").invoke(null));
        assertEquals(true, type.getMethod("named").invoke(null));
        assertEquals(true, type.getMethod("mixed").invoke(null));
        assertEquals(true, type.getMethod("grouped").invoke(null));
        assertEquals(32.0, type.getMethod("converted").invoke(null));
        final var output = new java.io.ByteArrayOutputStream();
        final var previousOut = System.out;
        try (final var capture = new java.io.PrintStream(output)) {
            System.setOut(capture);
            assertEquals(21, type.getMethod("ordered").invoke(null));
        }
        finally {
            System.setOut(previousOut);
        }
        assertEquals(
            "1\n2\n",
            output.toString(Charset.defaultCharset()).replace("\r\n", "\n")
        );
    }

    @Test
    void rejectsInvalidNamedArguments() {
        for (final String call : List.of(
            "f(a=1, unknown=2);",
            "f(a=1, a=2);",
            "f(1, a=2);",
            "f(a=1, 2);",
            "f(a=1);",
            "f(a=true, b=2);"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile("func f(a: i32, b: i32) {}\n" + call),
                call
            );
        }
        assertThrows(SemanticException.class, () -> compile("print(value=1);"));
        assertThrows(
            SemanticException.class,
            () -> compile("func f(a: i32) {}\nconst alias = f;\nalias(a=1);")
        );
    }

    @Test
    void nullableConversionsOnlyCastNullValuesLoadedAsObjects()
        throws Exception {
        final String source = """
            const nothing = null;
            func literal() i32 | null { return null; }
            func grouped() i32 | null { return ((null)); }
            func loaded() i32 | null { return nothing; }
            """;
        final var program = new Parser(new Lexer(source).getTokens()).parse();
        final byte[] bytes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generate();
        BytecodeUtil.verify(bytes);
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (final var method : node.methods) {
            if (
                !List.of("literal", "grouped", "loaded").contains(method.name)
            ) {
                continue;
            }
            int casts = 0;
            for (final var instruction : method.instructions) {
                if (instruction.getOpcode() == Opcodes.CHECKCAST) {
                    casts++;
                }
            }
            assertEquals(
                method.name.equals("loaded") ? 1 : 0,
                casts,
                method.name
            );
        }
        final Class<?> type = compile(program);
        assertNull(type.getMethod("literal").invoke(null));
        assertNull(type.getMethod("grouped").invoke(null));
        assertNull(type.getMethod("loaded").invoke(null));
    }

    @Test
    void nullableUnionsUsePreciseReferenceDescriptors() throws Exception {
        final String[] members =
            {"i8", "i16", "i32", "i64", "f32", "f64", "bool", "char", "string",
                    "[i32]", "[string]"};
        final String[] literals =
            {"7", "7", "7", "7", "1.5", "2.5", "true", "'x'", "\"hi\"",
                    "[1, 2]", "[\"hi\"]"};
        final Class<?>[] representations =
            {Byte.class, Short.class, Integer.class, Long.class, Float.class,
                    Double.class, Boolean.class, Character.class, String.class,
                    EldIntArray.class, EldObjectArray.class};
        for (int i = 0; i < members.length; i++) {
            final Class<?> type =
                compile(
                    """
                        const nothing = null;
                        var absent: %s | null = nothing;
                        var value: %s | null = %s;
                        var reordered: null | %s = value;
                        func identity(input: %s | null) %s | null { return input; }
                        const callback = identity;
                        var called = callback(value);
                        var calledNull = identity(nothing);
                        var elements: [%s | null] = [value, nothing];
                        var loaded = elements[0];
                        var loadedNull = elements[1];
                        var last: %s | null = nothing;
                        for (element : elements) { last = element; }
                        """
                        .formatted(
                            members[i],
                            members[i],
                            literals[i],
                            members[i],
                            members[i],
                            members[i],
                            members[i],
                            members[i]
                        )
                );
            final Class<?> representation = representations[i];
            for (final String field : new String[] {"absent", "value",
                    "reordered", "called", "calledNull", "loaded", "loadedNull",
                    "last"}) {
                assertEquals(
                    representation,
                    type.getField(field).getType(),
                    field
                );
            }
            assertEquals(
                representation,
                type.getMethod("identity", representation).getReturnType()
            );
            assertNull(type.getField("absent").get(null));
            assertNull(type.getField("calledNull").get(null));
            assertNull(type.getField("loadedNull").get(null));
            assertNull(type.getField("last").get(null));
            final Object value = type.getField("value").get(null);
            assertInstanceOf(representation, value);
            assertSame(value, type.getField("called").get(null));
            assertSame(value, type.getField("loaded").get(null));
        }
        final Class<?> type = compile("""
            var value: i32 | null = 1000;
            var general: i32 | string | null = value;
            var numbers: i32 | i64 | null = value;
            var onlyNull: null | null = null;
            var text: string | null = onlyNull;
            var equal = general == value;
            """);
        assertEquals(Object.class, type.getField("general").getType());
        assertEquals(Object.class, type.getField("numbers").getType());
        assertEquals(1000, type.getField("general").get(null));
        assertEquals(String.class, type.getField("text").getType());
        assertNull(type.getField("text").get(null));
        assertEquals(true, type.getField("equal").get(null));
    }

    private static int[] intValues(final Object value) {
        final EldIntArray array = assertInstanceOf(EldIntArray.class, value);
        final int[] result = new int[array.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = array.get(i);
        }
        return result;
    }

    @Test
    void grownArraysKeepTheirRuntimeTypeAcrossCallsAndIteration()
        throws Exception {
        final Class<?> type = compile("""
            var values: [i32] = [];
            func identity(items: [i32]) [i32] { return items; }
            func total() i32 {
                var sum = 0;
                for (value : values) { sum = sum + value; }
                return sum;
            }
            func last() i32 { return values[-1]; }
            func copy() [i32] { return values[:]; }
            """);
        assertEquals(EldIntArray.class, type.getField("values").getType());
        final EldIntArray values =
            (EldIntArray) type.getField("values").get(null);
        for (int i = 0; i < 101; i++) {
            values.add(i);
        }
        assertSame(
            values,
            type.getMethod("identity", EldIntArray.class).invoke(null, values)
        );
        assertEquals(5050, type.getMethod("total").invoke(null));
        assertEquals(100, type.getMethod("last").invoke(null));
        final EldIntArray copy =
            (EldIntArray) type.getMethod("copy").invoke(null);
        assertEquals(101, copy.size());
        assertNotSame(values, copy);
        assertEquals(values.toString(), copy.toString());
    }

    @Test
    void slicesCopyArraysWithOptionalAndNegativeBounds() throws Exception {
        final Class<?> type = compile("""
            var values = [1, 2, 3];
            var all = values[:];
            var tail = values[1:];
            var head = values[:2];
            var middle = values[1:2];
            var negativeEnd = values[:-1];
            var negativeStart = values[-2:];
            var negativeBoth = values[-2:-1];
            var full = values[0:3];
            var endEmpty = values[3:];
            var startEmpty = values[:0];
            var empty: [i32] = [];
            var emptyCopy = empty[:];
            func copy(input: [i32]) [i32] { return input[:]; }
            var returned = copy(values);
            all[0] = 99;
            """);
        assertArrayEquals(
            new int[] {1, 2, 3},
            intValues(type.getField("values").get(null))
        );
        assertArrayEquals(
            new int[] {99, 2, 3},
            intValues(type.getField("all").get(null))
        );
        for (final String name : List.of("tail", "negativeStart")) {
            assertArrayEquals(
                new int[] {2, 3},
                intValues(type.getField(name).get(null))
            );
        }
        for (final String name : List.of("head", "negativeEnd")) {
            assertArrayEquals(
                new int[] {1, 2},
                intValues(type.getField(name).get(null))
            );
        }
        for (final String name : List.of("middle", "negativeBoth")) {
            assertArrayEquals(
                new int[] {2},
                intValues(type.getField(name).get(null))
            );
        }
        for (final String name : List.of("full", "returned")) {
            assertArrayEquals(
                new int[] {1, 2, 3},
                intValues(type.getField(name).get(null))
            );
        }
        for (final String name : List
            .of("endEmpty", "startEmpty", "emptyCopy")) {
            assertArrayEquals(
                new int[0],
                intValues(type.getField(name).get(null))
            );
        }
    }

    @Test
    void intArraySlicesRejectInvalidBoundsAtRuntime() throws Exception {
        for (final String bounds : List
            .of("-2147483648:2147483647", "99:", ":-99", "0:4", "-4:2")) {
            final Class<?> type =
                compile(
                    "func invalid() [i32] { var values = [1, 2, 3]; return values["
                        + bounds + "]; }"
                );
            final InvocationTargetException error =
                assertThrows(
                    InvocationTargetException.class,
                    () -> type.getMethod("invalid").invoke(null)
                );
            assertInstanceOf(IndexOutOfBoundsException.class, error.getCause());
        }
        final Class<?> reversed =
            compile(
                "func invalid() [i32] { var values = [1, 2, 3]; return values[2:1]; }"
            );
        final InvocationTargetException error =
            assertThrows(
                InvocationTargetException.class,
                () -> reversed.getMethod("invalid").invoke(null)
            );
        assertInstanceOf(IndexOutOfBoundsException.class, error.getCause());
    }

    @Test
    void slicesPreserveElementTypesAndCopyReferencesShallowly()
        throws Exception {
        final String[] types =
            {"i8", "i16", "i32", "i64", "f32", "f64", "bool", "char", "string"};
        final String[] values =
            {"7", "7", "7", "7", "7.5", "7.5", "true", "'x'", "\"text\""};
        for (int i = 0; i < types.length; i++) {
            final Class<?> type =
                compile(
                    "var element: " + types[i] + " = " + values[i]
                        + "; var original = [element, element]; var sliced = original[-1:];"
                );
            final Object original = type.getField("original").get(null);
            final Object sliced = type.getField("sliced").get(null);
            assertEquals(original.getClass(), sliced.getClass());
            assertNotSame(original, sliced);
            assertEquals(1, assertInstanceOf(EldArray.class, sliced).size());
            assertEquals(
                original.getClass()
                    .getMethod("get", int.class)
                    .invoke(original, 1),
                sliced.getClass().getMethod("get", int.class).invoke(sliced, 0)
            );
        }
        final Class<?> type = compile("""
            var row = [1, 2];
            var matrix = [row];
            var copy = matrix[:];
            """);
        final EldObjectArray<?> original =
            (EldObjectArray<?>) type.getField("matrix").get(null);
        final EldObjectArray<?> copy =
            (EldObjectArray<?>) type.getField("copy").get(null);
        assertNotSame(original, copy);
        assertSame(original.get(0), copy.get(0));
    }

    @Test
    void sliceBoundsRunOnceInOrderAndKeepTheOriginalTarget() throws Exception {
        final Class<?> type = compile("""
            var values = [1, 2, 3];
            var calls = 0;
            func start() i32 {
                calls = calls * 10 + 1;
                values = [9, 8, 7];
                return 1;
            }
            func end() i32 { calls = calls * 10 + 2; return 3; }
            var sliced = values[start():end()];
            """);
        assertEquals(12, type.getField("calls").get(null));
        assertArrayEquals(
            new int[] {2, 3},
            intValues(type.getField("sliced").get(null))
        );
    }

    @Test
    void slicesRejectInvalidTargetsAndBounds() {
        for (final String source : List.of(
            "var values = 1; var result = values[:];",
            "var values = [1]; var result = values[true:];",
            "var values = [1]; var result = values[:1.5];",
            "var values = [1]; var index: i64 = 0; var result = values[index:];"
        )) {
            assertThrows(SemanticException.class, () -> compile(source));
        }
    }

    @Test
    void negativeIndicesReadFromTheEndAndKeepBoundsChecks() throws Exception {
        final Class<?> type = compile("""
            var values = [10, 20, 30];
            func read(index: i32) i32 { return values[index]; }
            func empty(index: i32) i32 {
                var values: [i32] = [];
                return values[index];
            }
            var strings = ["first", "last"];
            var last = strings[-1];
            var matrix = [[1, 2], [3, 4]];
            var row = matrix[-1];
            var nested = row[-2];
            """);
        for (int index = -3; index < 3; index++) {
            assertEquals(
                (index < 0 ? index + 3 : index) * 10 + 10,
                type.getMethod("read", int.class).invoke(null, index)
            );
        }
        assertEquals("last", type.getField("last").get(null));
        assertEquals(3, type.getField("nested").get(null));
        for (final int index : new int[] {-4, 3, Integer.MIN_VALUE,
                Integer.MAX_VALUE}) {
            final InvocationTargetException exception =
                assertThrows(
                    InvocationTargetException.class,
                    () -> type.getMethod("read", int.class).invoke(null, index)
                );
            assertInstanceOf(
                IndexOutOfBoundsException.class,
                exception.getCause()
            );
        }
        for (final int index : new int[] {-1, 0}) {
            final InvocationTargetException exception =
                assertThrows(
                    InvocationTargetException.class,
                    () -> type.getMethod("empty", int.class).invoke(null, index)
                );
            assertInstanceOf(
                IndexOutOfBoundsException.class,
                exception.getCause()
            );
        }
    }

    @Test
    void negativeIndexUpdatesEvaluateOperandsOnceInOrder() throws Exception {
        final Class<?> type = compile("""
            var values = [10, 20];
            var calls = 0;
            func index() i32 { calls = calls * 10 + 2; return -1; }
            func value() i32 { calls = calls * 10 + 3; return 40; }
            var read = values[index()];
            var assigned = values[index()] = value();
            var old = values[index()]++;
            var updated = values[index()]--;
            var wide: i64 = 9000000000;
            var longs = [wide];
            var wideOld = longs[-1]++;
            var wideUpdated = longs[-1]--;
            var wideAssigned = longs[-1] = 8000000000;
            """);
        assertEquals(22322, type.getField("calls").get(null));
        assertEquals(20, type.getField("read").get(null));
        assertEquals(40, type.getField("assigned").get(null));
        assertEquals(40, type.getField("old").get(null));
        assertEquals(41, type.getField("updated").get(null));
        assertArrayEquals(
            new int[] {10, 40},
            intValues(type.getField("values").get(null))
        );
        assertEquals(9000000000L, type.getField("wideOld").get(null));
        assertEquals(9000000001L, type.getField("wideUpdated").get(null));
        assertEquals(8000000000L, type.getField("wideAssigned").get(null));
        final EldLongArray longs =
            (EldLongArray) type.getField("longs").get(null);
        assertEquals(1, longs.size());
        assertEquals(8000000000L, longs.get(0));
    }

    @Test
    void floatLiteralNarrowingKeepsDoubleExpressionTypeAndConvertsAtRuntime()
        throws Exception {
        final Program program =
            new Parser(new Lexer("var value: f32 = 1.5;").getTokens()).parse();
        final var model = new SemanticAnalyzer().analyze(program);
        final var initializer =
            ((VariableDeclaration) program.items().getFirst()).initializer();
        assertEquals(
            com.github.andreasarvidsson.eld.semantic.BuiltinType.F64,
            model.getExpressionType(initializer)
        );
        assertEquals(
            com.github.andreasarvidsson.eld.semantic.BuiltinType.F32,
            model.getConversionType(initializer)
        );
        final Class<?> type = compile("""
            const folded: f32 = 1.0000000596046448;
            var runtime: f32 = 1.0000000596046448;
            var grouped: f32 = -(1.0000000596046448);
            func identity(value: f32) f32 { return value; }
            var argument = identity(1.0000000596046448);
            func result() f32 { return +(1.0000000596046448); }
            """);
        // Parsing as f64 first rounds to the f32 midpoint, then D2F rounds to even.
        // Parsing the original decimal directly as f32 would round upward.
        assertEquals(1.0f, type.getField("folded").get(null));
        assertEquals(1.0f, type.getField("runtime").get(null));
        assertEquals(-1.0f, type.getField("grouped").get(null));
        assertEquals(1.0f, type.getField("argument").get(null));
        assertEquals(1.0f, type.getMethod("result").invoke(null));
    }

    @Test
    void decimalLiteralsDefaultToDoubleAndHonorExplicitFloatTypes()
        throws Exception {
        final Class<?> type = compile("""
            const inferred = 1.23456789012345;
            var runtime = 1.23456789012345;
            var single: f32 = 1.23456789012345;
            const signed: f32 = -(1.23456789012345);
            func identity(value: f32) f32 { return value; }
            func literal() f32 { return +(1.23456789012345); }
            var argument = identity(-(1.23456789012345));
            var returned = literal();
            var mixed = single + 0.1;
            """);
        assertEquals(double.class, type.getField("inferred").getType());
        assertEquals(1.23456789012345, type.getField("inferred").get(null));
        assertEquals(1.23456789012345, type.getField("runtime").get(null));
        assertEquals(1.2345679f, type.getField("single").get(null));
        assertEquals(-1.2345679f, type.getField("signed").get(null));
        assertEquals(-1.2345679f, type.getField("argument").get(null));
        assertEquals(1.2345679f, type.getField("returned").get(null));
        assertEquals(double.class, type.getField("mixed").getType());
        assertEquals(
            (double) 1.2345679f + 0.1,
            type.getField("mixed").get(null)
        );
        assertThrows(
            SemanticException.class,
            () -> compile("var a = 1.25; var b: f32 = a;")
        );
        assertThrows(
            SemanticException.class,
            () -> compile("func f(a: f32) {} var a = 1.25; f(a);")
        );
    }

    @Test
    void numericOperatorsUseJavaPromotionForEveryTypePair() throws Exception {
        final String[] types =
            {"i8", "i16", "i32", "i64", "f32", "f64", "char"};
        for (final String left : types) {
            for (final String right : types) {
                final String a = left.equals("char") ? "'A'" : "2";
                final String b = right.equals("char") ? "'A'" : "2";
                final Class<?> type =
                    compile(
                        "var a: " + left + " = " + a + "; var b: " + right
                            + " = " + b
                            + "; var sum = a + b; var difference = a - b; var product = a * b;"
                            + " var quotient = a / b; var remainder = a % b; var equal = a == b; var less = a < b;"
                    );
                final Class<?> expectedType =
                    left.equals("f64") || right.equals("f64")
                        ? double.class
                        : left.equals("f32") || right.equals("f32")
                            ? float.class
                            : left.equals("i64") || right.equals("i64")
                                ? long.class
                                : int.class;
                for (final String field : List.of(
                    "sum",
                    "difference",
                    "product",
                    "quotient",
                    "remainder"
                )) {
                    assertEquals(
                        expectedType,
                        type.getField(field).getType(),
                        left + " / " + right
                    );
                }
                final int av = left.equals("char") ? 65 : 2;
                final int bv = right.equals("char") ? 65 : 2;
                assertEquals(
                    (double) (av + bv),
                    ((Number) type.getField("sum").get(null)).doubleValue()
                );
                assertEquals(
                    (double) (av - bv),
                    ((Number) type.getField("difference").get(null))
                        .doubleValue()
                );
                assertEquals(
                    (double) (av * bv),
                    ((Number) type.getField("product").get(null)).doubleValue()
                );
                assertEquals(av == bv, type.getField("equal").get(null));
                assertEquals(av < bv, type.getField("less").get(null));
            }
        }
    }

    @Test
    void smallUnaryOperandsPromoteAndFloatingPromotionPreservesRounding()
        throws Exception {
        final Class<?> type = compile("""
            var small: i8 = -128;
            var shortValue: i16 = -32768;
            var character = 'A';
            var negated = -small;
            var positive = +shortValue;
            var code = +character;
            var negatedCharacter = -character;
            var sum = small + small;
            var old = small++;
            var wide: f64 = 0;
            var promoted = wide + 0.1;
            const folded = 'A' + 0.1;
            var runtime = character + 0.1;
            var large: i64 = 16777217;
            var single: f32 = 0;
            var rounded = large + single;
            """);
        assertEquals(128, type.getField("negated").get(null));
        assertEquals(-32768, type.getField("positive").get(null));
        assertEquals(65, type.getField("code").get(null));
        assertEquals(-65, type.getField("negatedCharacter").get(null));
        assertEquals(-256, type.getField("sum").get(null));
        assertEquals((byte) -128, type.getField("old").get(null));
        assertEquals((byte) -127, type.getField("small").get(null));
        assertEquals(0.1, type.getField("promoted").get(null));
        assertEquals(65 + 0.1, type.getField("folded").get(null));
        assertEquals(
            type.getField("folded").get(null),
            type.getField("runtime").get(null)
        );
        assertEquals(16777216f, type.getField("rounded").get(null));
        assertThrows(
            SemanticException.class,
            () -> compile("var a: i8 = 1; var b: i8 = a + a;")
        );
    }

    @Test
    void sizedNumbersUseTheirJvmTypesAndSupportWidening() throws Exception {
        final Class<?> type = compile("""
            const smallest: i8 = -128;
            const small: i16 = 32767;
            const wide: i64 = 9223372036854775807;
            const minimum: i64 = -(9223372036854775808);
            var inferred = 2147483648;
            var single: f32 = 1.25;
            var precise: f64 = 1.23456789012345;
            func mix(a: i8, b: i16, c: i32, d: i64, e: f32, f: f64) f64 {
                var local: i64 = d + c;
                var real: f64 = f + e;
                local++;
                real++;
                return local + real + a + b;
            }
            var result = mix(1, 2, 3, 4, 5.0, 6.0);
            """);
        assertEquals(byte.class, type.getField("smallest").getType());
        assertEquals((byte) -128, type.getField("smallest").get(null));
        assertEquals((short) 32767, type.getField("small").get(null));
        assertEquals(Long.MAX_VALUE, type.getField("wide").get(null));
        assertEquals(Long.MIN_VALUE, type.getField("minimum").get(null));
        assertEquals(2147483648L, type.getField("inferred").get(null));
        assertEquals(1.25f, type.getField("single").get(null));
        assertEquals(1.23456789012345, type.getField("precise").get(null));
        assertEquals(23.0, type.getField("result").get(null));
    }

    @Test
    void sizedArraysAssignmentsAndOverflowVerifyAndExecute() throws Exception {
        final String[] types = {"i8", "i16", "i64", "f64"};
        final String[] initial = {"127", "32767", "2147483648", "1.25"};
        final String[] assigned = {"126", "32766", "9000000000", "4.5"};
        final Object[] oldValues =
            {(byte) 127, (short) 32767, 2147483648L, 1.25};
        final Object[] newValues =
            {(byte) 127, (short) 32767, 9000000001L, 5.5};
        final Object[] finalValues =
            {(byte) -128, (short) -32768, 9000000002L, 6.5};
        final Range range = new Range(1, 1, 1, 2);
        for (int i = 0; i < types.length; i++) {
            final Program declarations =
                new Parser(
                    new Lexer(
                        "var value: " + types[i] + " = " + initial[i]
                            + "; var values = [value];"
                    ).getTokens()
                ).parse();
            final List<com.github.andreasarvidsson.eld.parser.BlockItem> items =
                new ArrayList<>(declarations.items());
            final SubscriptExpression index =
                new SubscriptExpression(
                    new IdentifierExpression("values", range),
                    new LiteralExpression(LiteralKind.INT, "0", range),
                    range
                );
            items.add(
                new VariableDeclaration(
                    Mutability.CONST,
                    new IdentifierDeclaration("old", range),
                    null,
                    new PostfixExpression(
                        index,
                        PostfixOperator.INCREMENT,
                        range
                    ),
                    range
                )
            );
            items.add(
                new VariableDeclaration(
                    Mutability.CONST,
                    new IdentifierDeclaration("assigned", range),
                    null,
                    new AssignmentExpression(
                        index,
                        new LiteralExpression(
                            i == 3 ? LiteralKind.FLOAT : LiteralKind.INT,
                            assigned[i],
                            range
                        )
                    ),
                    range
                )
            );
            items.add(
                new VariableDeclaration(
                    Mutability.CONST,
                    new IdentifierDeclaration("updated", range),
                    null,
                    new UnaryExpression(UnaryOperator.INCREMENT, index, range),
                    range
                )
            );
            items.add(
                new ExpressionStatement(
                    new PostfixExpression(
                        index,
                        PostfixOperator.INCREMENT,
                        range
                    ),
                    range
                )
            );
            final Class<?> type = compile(new Program(items, range));
            assertEquals(oldValues[i], type.getField("old").get(null));
            assertEquals(newValues[i], type.getField("updated").get(null));
            assertEquals(
                finalValues[i],
                type.getField("values")
                    .getType()
                    .getMethod("get", int.class)
                    .invoke(type.getField("values").get(null), 0)
            );
        }
    }

    @Test
    void wideNumbersWorkInLoopsSwitchesAndInstanceFields() throws Exception {
        final Class<?> type = compile("""
            var longs = [2147483648, 9000000000];
            var total: i64 = 0;
            for (value : longs) { total = total + value; }
            func update() i64 { var x: i64 = 1; x = 4; x++; return -x; }
            var negative = update();
            var comparison = total > 9000000000;
            var selected = switch (total) { case 11147483648 => 7 else => 9 };
            var real: f64 = 2.25;
            var matchValue: f64 = 2.25;
            var decimal = switch (real) { case matchValue => 3 else => 5 };
            var precise: f64 = -(1.23456789012345);
            """);
        assertEquals(11147483648L, type.getField("total").get(null));
        assertEquals(-5L, type.getField("negative").get(null));
        assertEquals(true, type.getField("comparison").get(null));
        assertEquals(7, type.getField("selected").get(null));
        assertEquals(3, type.getField("decimal").get(null));
        assertEquals(-1.23456789012345, type.getField("precise").get(null));
        final Class<?> counter = compileClass("""
            class Counter {
                var count: i64 = 1;
                var real: f64 = 1.25;
                func old() i64 { return count++; }
                func next() f64 { real++; return real; }
                func set() i64 { return count = 9000000000; }
            }
            """, "Test$Counter");
        final Object instance = counter.getConstructor().newInstance();
        assertEquals(1L, counter.getMethod("old").invoke(instance));
        assertEquals(2.25, counter.getMethod("next").invoke(instance));
        assertEquals(9000000000L, counter.getMethod("set").invoke(instance));
    }

    @Test
    void printsEveryNumericSize() throws Exception {
        final Program program = new Parser(new Lexer("""
            var a: i8 = -128;
            var b: i16 = 32767;
            var c: i32 = 123;
            var d: i64 = 9223372036854775807;
            var e: f32 = 1.25;
            var f: f64 = 1.23456789012345;
            print(a); print(b); print(c); print(d); print(e); print(f);
            """).getTokens()).parse();
        final var classes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generateClasses();
        assertEquals(
            "-128\n32767\n123\n9223372036854775807\n1.25\n1.23456789012345\n",
            BytecodeRunner.run(classes)
        );
    }

    @Test
    void rejectsNumericOverflowNarrowingAndOldTypeNames() {
        for (final String source : List.of(
            "var x: i8 = 128;",
            "var x: i8 = -129;",
            "var x: i16 = 32768;",
            "var x: i16 = -32769;",
            "var x: i32 = 2147483648;",
            "var x = 9223372036854775808;",
            "var x = -9223372036854775809;",
            "var x: i64 = 1; var y: i32 = x;",
            "var x: f64 = 1.0; var y: f32 = x;",
            "var x: int = 1;",
            "var x: float = 1.0;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
    }

    @Test
    void integerSwitchUsesTableForDenseKeys() throws Exception {
        final String source =
            """
                var calls = 0;
                func subject() i32 { calls++; return 2; }
                func choose(value: i32) i32 {
                    return switch (value) {
                        case 2, 3 { yield 20; }
                        case -1 => 10
                        case 0 => 11
                        case 2 => 99
                        else => 50
                    };
                }
                var selected = 0;
                switch (subject()) { case 1 => selected = 1 case 2, 3 => selected = 2 }
                switch (99) { case 1, 2 => selected = 99 }
                """;
        final var node = inspect(source);
        final var choose =
            node.methods.stream()
                .filter(m -> m.name.equals("choose"))
                .findFirst()
                .orElseThrow();
        final var table =
            java.util.Arrays.stream(choose.instructions.toArray())
                .filter(TableSwitchInsnNode.class::isInstance)
                .map(TableSwitchInsnNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(-1, table.min);
        assertEquals(3, table.max);
        assertSame(table.dflt, table.labels.get(2));
        assertSame(table.labels.get(3), table.labels.get(4));
        final Class<?> type = compile(source);
        assertEquals(20, type.getMethod("choose", int.class).invoke(null, 2));
        assertEquals(20, type.getMethod("choose", int.class).invoke(null, 3));
        assertEquals(10, type.getMethod("choose", int.class).invoke(null, -1));
        assertEquals(50, type.getMethod("choose", int.class).invoke(null, 1));
        assertEquals(50, type.getMethod("choose", int.class).invoke(null, 99));
        assertEquals(1, type.getField("calls").get(null));
        assertEquals(2, type.getField("selected").get(null));
    }

    @Test
    void integerSwitchUsesLookupForSparseAndExtremeKeys() throws Exception {
        final String source = """
            func choose(value: i32) i32 {
                return switch (value) {
                    case 2147483647 => 1
                    case -2147483648 => 2
                    case (500 + 500), -1_000 => 3
                    else { yield 4; }
                };
            }
            """;
        final var choose =
            inspect(source).methods.stream()
                .filter(m -> m.name.equals("choose"))
                .findFirst()
                .orElseThrow();
        final var lookup =
            java.util.Arrays.stream(choose.instructions.toArray())
                .filter(LookupSwitchInsnNode.class::isInstance)
                .map(LookupSwitchInsnNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(
            List.of(Integer.MIN_VALUE, -1000, 1000, Integer.MAX_VALUE),
            lookup.keys
        );
        final Class<?> type = compile(source);
        assertEquals(
            1,
            type.getMethod("choose", int.class).invoke(null, Integer.MAX_VALUE)
        );
        assertEquals(
            2,
            type.getMethod("choose", int.class).invoke(null, Integer.MIN_VALUE)
        );
        assertEquals(3, type.getMethod("choose", int.class).invoke(null, 1000));
        assertEquals(
            3,
            type.getMethod("choose", int.class).invoke(null, -1000)
        );
        assertEquals(4, type.getMethod("choose", int.class).invoke(null, 0));
    }

    @Test
    void integerSwitchRetainsOrderedEvaluationForRuntimeMatches()
        throws Exception {
        final String source =
            """
                var calls = 0;
                func match() i32 { calls++; return 2; }
                func choose(value: i32) i32 {
                    return switch (value) {
                        case 1 => 10
                        case match(), match() => 20
                        else => 30
                    };
                }
                func throwing(value: i32) i32 {
                    return switch (value) { case 1 => 10 case 1 / 0 => 20 else => 30 };
                }
                """;
        for (final var method : inspect(source).methods) {
            for (final var instruction : method.instructions) {
                assertFalse(instruction instanceof TableSwitchInsnNode);
                assertFalse(instruction instanceof LookupSwitchInsnNode);
            }
        }
        final Class<?> type = compile(source);
        assertEquals(10, type.getMethod("choose", int.class).invoke(null, 1));
        assertEquals(0, type.getField("calls").get(null));
        assertEquals(20, type.getMethod("choose", int.class).invoke(null, 2));
        assertEquals(1, type.getField("calls").get(null));
        assertEquals(30, type.getMethod("choose", int.class).invoke(null, 9));
        assertEquals(3, type.getField("calls").get(null));
        assertEquals(10, type.getMethod("throwing", int.class).invoke(null, 1));
        final var error =
            assertThrows(
                InvocationTargetException.class,
                () -> type.getMethod("throwing", int.class).invoke(null, 9)
            );
        assertInstanceOf(ArithmeticException.class, error.getCause());
    }

    @Test
    void switchEvaluatesSubjectOnceAndNeverFallsThrough() throws Exception {
        final Class<?> type = compile("""
            var calls = 0;
            var matches = 0;
            func subject() i32 { calls++; return 2; }
            func match() i32 { matches++; return 2; }
            const result = switch (subject()) {
                case 1, match(), match() => 20
                case 2 => 99
                else => 0
            };
            var side = 0;
            switch (2) {
                case 1 => print()
                case 2 => side++
                case 3 => "unused"
            }
            switch (9) { case 1 => side++ }
            (switch (9) { case 1 => side++ });
            const text = switch ("a" + "b") { case "ab" => "yes" else => "no" };
            const floating = switch (1.5) { case 1.5 => 2.5 else => 0.5 };
            const boolean = switch (true) { case true => 7 else => 8 };
            const character = switch ('a') { case 'a' => 9 else => 0 };
            var updates = 0;
            for (; updates < 2; switch (updates) { case 0, 1 => updates++ }) {}
            """);
        assertEquals(1, type.getField("calls").get(null));
        assertEquals(1, type.getField("matches").get(null));
        assertEquals(20, type.getField("result").get(null));
        assertEquals(1, type.getField("side").get(null));
        assertEquals("yes", type.getField("text").get(null));
        assertEquals(2.5, type.getField("floating").get(null));
        assertEquals(7, type.getField("boolean").get(null));
        assertEquals(9, type.getField("character").get(null));
        assertEquals(2, type.getField("updates").get(null));
    }

    @Test
    void switchBlocksYieldToTheirOwnExpression() throws Exception {
        final Class<?> type = compile("""
            const result = 10 + switch (2) {
                case 1 => 0
                else {
                    const inner = switch (1) { case 1 { yield 3; } else => 4 };
                    switch (1) { case 1 { yield "discarded"; } }
                    if (inner == 3) { yield inner + 2; }
                    else { yield 0; }
                }
            };
            const widened: f32 = switch (0) { else => 4 };
            func early(value: i32) i32 {
                const n = switch (value) { case 1 { return 8; } else => 2 };
                return n;
            }
            var count = 0;
            for (var i = 0; i < 4; i++) {
                switch (i) {
                    case 0 { continue; }
                    case 2 { break; }
                    else => count++
                }
            }
            """);
        assertEquals(15, type.getField("result").get(null));
        assertEquals(4.0f, type.getField("widened").get(null));
        assertEquals(8, type.getMethod("early", int.class).invoke(null, 1));
        assertEquals(2, type.getMethod("early", int.class).invoke(null, 0));
        assertEquals(1, type.getField("count").get(null));
    }

    @Test
    void switchRejectsInvalidValuePathsAndStillChecksDiscardedBranches() {
        for (final String source : List.of(
            "const x = switch (1) { case 1 => 2 };",
            "const x = switch (1) { case 1 => 2 else => 2.5 };",
            "const x: f32 = switch (1) { case 1 => 2 else => 2.5 };",
            "const x = switch (1) { case 1 { 2; } else => 3 };",
            "const x = switch (1) { case 1 { if (true) { yield 2; } } else => 3 };",
            "const x = switch (1) { case 1 => print() else => 3 };",
            "switch (1) { case 1 => missing }",
            "switch (1) { case true => 0 }",
            "switch (print()) {}",
            "yield 1;",
            "const x = switch (1) { else { switch (1) { else { yield 1; } } } };"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
        for (final String source : List.of(
            "switch (1) { else => 0 else => 1 }",
            "switch (1) { else => 0 case 1 => 1 }",
            "switch (1) { case 1 2; }",
            "switch (1) { case 1 => { yield 2; } }",
            "switch (1) { else => { yield 2; } }",
            "switch (1) { case 1 => 2"
        )) {
            assertThrows(
                com.github.andreasarvidsson.eld.parser.ParserException.class,
                () -> compile(source),
                source
            );
        }
    }

    @Test
    void switchSupportsBlockBranchesWithoutArrows() throws Exception {
        final Class<?> type = compile("""
            func choose(value: i32) i32 {
                return switch (value) {
                    case 1, 2 { yield 10; }
                    else { yield 20; }
                };
            }
            var side = 0;
            switch (9) { else { side++; } }
            """);
        assertEquals(10, type.getMethod("choose", int.class).invoke(null, 2));
        assertEquals(20, type.getMethod("choose", int.class).invoke(null, 9));
        assertEquals(1, type.getField("side").get(null));
    }

    @Test
    void switchExpressionBranchesDoNotRequireSemicolons() throws Exception {
        final Class<?> type =
            compile(
                """
                    func choose(value: i32) i32 {
                        return switch (value) {
                            case 1, 2 => 10 +
                                2
                            case 3 { yield 30; }
                            case 4 => 40
                            else => 50
                        };
                    }
                    var side = 0;
                    switch (2) { case 1 {} else => side++ }
                    const nested = switch (0) { else => switch (1) { case 1 => 7 else => 8 } };
                    """
            );
        assertEquals(12, type.getMethod("choose", int.class).invoke(null, 2));
        assertEquals(30, type.getMethod("choose", int.class).invoke(null, 3));
        assertEquals(40, type.getMethod("choose", int.class).invoke(null, 4));
        assertEquals(50, type.getMethod("choose", int.class).invoke(null, 9));
        assertEquals(1, type.getField("side").get(null));
        assertEquals(7, type.getField("nested").get(null));
    }

    @Test
    void conditionalBranchesRequireMatchingTypesBeforeAssignmentConversion()
        throws Exception {
        for (final String expression : List.of(
            "true ? 1 : 2.5",
            "false ? 1.5 : 2",
            "if (true) { yield 1; } else { yield 2.5; }",
            "if (true) { yield 1.5; } else { yield 2; }",
            "if (true) { yield 1; } elif (false) { yield 2.5; } else { yield 3; }",
            "if (true) { if (false) { yield 1.5; } yield 1; } else { yield 2; }"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile("const value = " + expression + ";")
            );
            assertThrows(
                SemanticException.class,
                () -> compile("const value: f32 = " + expression + ";")
            );
        }
        final Class<?> type = compile("""
            const ternary: f32 = true ? 1 : 2;
            const conditional: f32 = if (false) { yield 3; } else { yield 4; };
            var assigned: f32 = 0;
            assigned = true ? 5 : 6;
            func result() f32 {
                assigned = if (true) { yield 7; } else { yield 8; };
                return assigned;
            }
            const floating = true ? 1.5 : 2.5;
            """);
        assertEquals(1.0f, type.getField("ternary").get(null));
        assertEquals(4.0f, type.getField("conditional").get(null));
        assertEquals(5.0f, type.getField("assigned").get(null));
        assertEquals(7.0f, type.getMethod("result").invoke(null));
        assertEquals(1.5, type.getField("floating").get(null));
    }

    @Test
    void decodesCharacterEscapesInConstantsAndRuntimeExpressions()
        throws Exception {
        final Class<?> type = compile("""
            const newline = '\\n';
            const carriage = '\\r';
            const tab = '\\t';
            const backspace = '\\b';
            const formFeed = '\\f';
            const quote = '\\'';
            const doubleQuote = '\\"';
            const slash = '\\\\';
            func runtimeNewline() char { return '\\n'; }
            func runtimeSlash() char { return '\\\\'; }
            """);
        assertEquals('\n', type.getField("newline").get(null));
        assertEquals('\r', type.getField("carriage").get(null));
        assertEquals('\t', type.getField("tab").get(null));
        assertEquals('\b', type.getField("backspace").get(null));
        assertEquals('\f', type.getField("formFeed").get(null));
        assertEquals('\'', type.getField("quote").get(null));
        assertEquals('"', type.getField("doubleQuote").get(null));
        assertEquals('\\', type.getField("slash").get(null));
        assertEquals('\n', type.getMethod("runtimeNewline").invoke(null));
        assertEquals('\\', type.getMethod("runtimeSlash").invoke(null));
    }

    @Test
    void standaloneExpressionsAndAssignmentsWorkInFiles() throws Exception {
        final Class<?> type = compile("""
            var count = 1;
            1 + 2;
            count;
            count = count + 1;
            func next() i32 { count++; return count; }
            (next());
            next() + 10;
            func result() i32 { return count; }
            """);
        assertEquals(4, type.getMethod("result").invoke(null));
    }

    @Test
    void standaloneAssignmentsStillRequireMutableTargets() {
        assertThrows(SemanticException.class, () -> compile("""
            const count = 1;
            count = 2;
            """));
        assertThrows(SemanticException.class, () -> compile("1 = 2;"));
    }

    @Test
    void executesBuiltinPrintAndFunctionReferences() throws Exception {
        final Program program = new Parser(new Lexer("""
            print("direct");
            const log = print;
            log("reference");
            print(42);
            print(1.5);
            print(true);
            print('x');
            print(null);
            print(['h', 'i']);
            print();
            log(7);
            log(2.5);
            log(false);
            log('z');
            log(null);
            log(['o', 'k']);
            log();
            func greet() { print("nested"); }
            greet();
            """).getTokens()).parse();
        final var classes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generateClasses();
        for (final byte[] bytecode : classes.values()) {
            BytecodeUtil.verify(bytecode);
        }
        assertEquals(
            "direct\nreference\n42\n1.5\ntrue\nx\nnull\n[h, i]\n\n7\n2.5\nfalse\nz\nnull\n[o, k]\n\nnested\n",
            BytecodeRunner.run(classes)
        );
    }

    @Test
    void printAcceptsObjectsAndRejectsInvalidArguments() throws Exception {
        final Program program = new Parser(new Lexer("""
            const log = print;
            print([1, 2]);
            log([true, false]);
            func value() i32 { return 1; }
            print(value);
            """).getTokens()).parse();
        final var classes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generateClasses();
        for (final byte[] bytecode : classes.values()) {
            BytecodeUtil.verify(bytecode);
        }
        final String output = BytecodeRunner.run(classes);
        assertEquals("[1, 2]\n[true, false]\nMethodHandle()int\n", output);
        assertThrows(SemanticException.class, () -> compile("print(1, 2);"));
        assertThrows(SemanticException.class, () -> compile("print(print());"));
        assertThrows(
            SemanticException.class,
            () -> compile("const log = print; log(1, 2);")
        );
        assertThrows(
            SemanticException.class,
            () -> compile("const log = print; log(print());")
        );
    }

    @Test
    void userFunctionCanShadowBuiltinPrint() throws Exception {
        final Class<?> type = compile("""
            func print(value: i32) i32 { return value + 1; }
            func result() i32 { return print(41); }
            """);
        assertEquals(42, type.getMethod("result").invoke(null));
    }

    @Test
    void storesInstanceFieldsInReceiverValueOrder() throws Exception {
        final Program program = new Parser(new Lexer("""
            class Counter {
                const initial = 10;
                var count = initial;
                var floating: f32 = 1.5;
                var text = "hello";
                func postfix() i32 { return count++; }
                func next() i32 { return count++; }
                func decrement() f32 { return floating--; }
            }
            """).getTokens()).parse();
        final var classes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generateClasses();
        final var node = new ClassNode();
        new ClassReader(classes.get("Test$Counter")).accept(node, 0);
        for (final var method : node.methods) {
            for (final var instruction : method.instructions) {
                assertNotEquals(Opcodes.SWAP, instruction.getOpcode());
            }
        }
        final Class<?> type = loadClass(classes, "Test$Counter");
        final Object instance = type.getConstructor().newInstance();
        assertEquals(10, type.getField("count").get(instance));
        assertEquals("hello", type.getField("text").get(instance));
        assertEquals(10, type.getMethod("postfix").invoke(instance));
        assertEquals(11, type.getMethod("next").invoke(instance));
        assertEquals(12, type.getField("count").get(instance));
        assertEquals(1.5f, type.getMethod("decrement").invoke(instance));
        assertEquals(0.5f, type.getField("floating").get(instance));
    }

    @Test
    void omitsUnreachableInstructionsAfterTerminatingPaths() throws Exception {
        for (final String body : List.of(
            "while (true) { break; } return 7;",
            "do { break; } while (true); return 7;",
            "for (var i = 0; i < 3; i++) { break; } return 7;",
            "for (value : [1]) { break; } return 7;",
            "do { return 7; } while (true);",
            "for (;;) { return 7; }",
            "if (true) { return 7; } elif (false) { return 8; } else { return 9; }",
            "return 7; var unused = 99;"
        )) {
            final String source = "func result() i32 { " + body + " }";
            final var node = inspect(source);
            for (final var method : node.methods) {
                for (final var instruction : method.instructions) {
                    assertNotEquals(Opcodes.NOP, instruction.getOpcode(), body);
                    assertNotEquals(
                        Opcodes.ATHROW,
                        instruction.getOpcode(),
                        body
                    );
                }
            }
            assertEquals(
                7,
                compile(source).getMethod("result").invoke(null),
                body
            );
        }
    }

    @Test
    void retainsContinueTargetsAndNestedLoopExits() throws Exception {
        final String source = """
            func result() i32 {
                var count = 0;
                for (var i = 0; i < 3; i++) {
                    while (true) { break; }
                    var old = count++;
                    continue;
                }
                do {
                    var old = count++;
                    continue;
                } while (count < 5);
                for (value : [1, 2]) {
                    var old = count++;
                    continue;
                }
                return count;
            }
            """;
        assertEquals(7, compile(source).getMethod("result").invoke(null));
        for (final var method : inspect(source).methods) {
            for (final var instruction : method.instructions) {
                assertNotEquals(Opcodes.NOP, instruction.getOpcode());
                assertNotEquals(Opcodes.ATHROW, instruction.getOpcode());
            }
        }
    }

    private static Class<?> loadClass(
        final Map<String, byte[]> classes,
        final String name
    )
        throws ClassNotFoundException {
        for (final byte[] bytecode : classes.values()) {
            BytecodeUtil.verify(bytecode);
        }
        return new ClassLoader() {
            @Override
            protected Class<?> findClass(final String binaryName)
                throws ClassNotFoundException {
                final byte[] bytecode = classes.get(binaryName);
                if (bytecode == null) {
                    throw new ClassNotFoundException(binaryName);
                }
                return defineClass(binaryName, bytecode, 0, bytecode.length);
            }
        }.loadClass(name);
    }

    private static Class<?> compileClass(final String source, final String name)
        throws ClassNotFoundException {
        final Program program =
            new Parser(new Lexer(source).getTokens()).parse();
        return loadClass(
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generateClasses(),
            name
        );
    }

    @Test
    void initializesFieldsPerInstanceAndUsesModuleFunctions() throws Exception {
        final Class<?> type = compileClass("""
            var seed = 2;
            func next() i32 { return seed++; }
            class Counter {
                const first = next();
                const second = next();
                var count = first;
                var widened: f32 = count;
                var values = [1, 2];
                var zero: i32 = 0;
                var text: string = "";
                func increment() i32 { return count++; }
                func add(delta: i32) i32 { return count + delta; }
                func shadow(count: i32) i32 { return count; }
            }
            """, "Test$Counter");
        final Object first = type.getConstructor().newInstance();
        final Object second = type.getConstructor().newInstance();
        assertEquals(2, type.getField("first").get(first));
        assertEquals(3, type.getField("second").get(first));
        assertEquals(4, type.getField("first").get(second));
        assertEquals(5, type.getField("second").get(second));
        assertEquals(2.0f, type.getField("widened").get(first));
        assertEquals(0, type.getField("zero").get(first));
        assertEquals("", type.getField("text").get(first));
        assertNotSame(
            type.getField("values").get(first),
            type.getField("values").get(second)
        );
        assertEquals(2, type.getMethod("increment").invoke(first));
        assertEquals(3, type.getField("count").get(first));
        assertEquals(4, type.getField("count").get(second));
        assertEquals(8, type.getMethod("add", int.class).invoke(first, 5));
        assertEquals(9, type.getMethod("shadow", int.class).invoke(first, 9));
        assertFalse(
            Modifier.isStatic(type.getMethod("increment").getModifiers())
        );
        assertEquals(6, type.getDeclaringClass().getField("seed").get(null));
    }

    @Test
    void invokesInstanceMethodsAndBindsMethodReferences() throws Exception {
        final Class<?> type = compileClass("""
            class Counter {
                var count = 10;
                func step() i32 { return count++; }
                const initial = step();
                const callback = step;
                func direct() i32 { return step(); }
                func indirect() i32 { return callback(); }
                func recursive(n: i32) i32 {
                    if (n <= 1) { return count; }
                    return recursive(n - 1) + 1;
                }
            }
            """, "Test$Counter");
        final Object first = type.getConstructor().newInstance();
        final Object second = type.getConstructor().newInstance();
        assertEquals(10, type.getField("initial").get(first));
        assertEquals(11, type.getMethod("direct").invoke(first));
        assertEquals(12, type.getMethod("indirect").invoke(first));
        assertEquals(11, type.getMethod("indirect").invoke(second));
        assertEquals(
            15,
            type.getMethod("recursive", int.class).invoke(first, 3)
        );
    }

    @Test
    void emitsControlFlowInConstructorsAndInstanceMethods() throws Exception {
        final Class<?> type = compileClass("""
            class Counter {
                var count = 0;
                for (var i = 0; i < 3; i++) { var ignored = count++; }
                func advance(limit: i32) i32 {
                    for (var i = 0; i < limit; i++) {
                        if (i == 1) { continue; }
                        var ignored = count++;
                    }
                    return count;
                }
                var floating: f32 = 1.5;
                func floatStep() f32 { return floating++; }
            }
            """, "Test$Counter");
        final Object instance = type.getConstructor().newInstance();
        assertEquals(3, type.getField("count").get(instance));
        assertEquals(
            6,
            type.getMethod("advance", int.class).invoke(instance, 4)
        );
        assertEquals(1.5f, type.getMethod("floatStep").invoke(instance));
        assertEquals(2.5f, type.getField("floating").get(instance));
    }

    @Test
    void resolvesSameNamedMembersOnTheirOwnClass() throws Exception {
        final Program program = new Parser(new Lexer("""
            const value = 100;
            func read() i32 { return value; }
            class First {
                var value = 1;
                func read() i32 { return value; }
                func call() i32 { return read(); }
            }
            class Second {
                var value = 2;
                func read() i32 { return value; }
                func call() i32 { return read(); }
            }
            """).getTokens()).parse();
        final var classes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generateClasses();
        final Class<?> first = loadClass(classes, "Test$First");
        final Class<?> second = loadClass(classes, "Test$Second");
        assertEquals(
            1,
            first.getMethod("call").invoke(first.getConstructor().newInstance())
        );
        assertEquals(
            2,
            second.getMethod("call")
                .invoke(second.getConstructor().newInstance())
        );
        assertEquals(
            100,
            first.getDeclaringClass().getMethod("read").invoke(null)
        );
    }

    @Test
    void generatesLoadableDeclaredClassesAlongsideModule() throws Exception {
        final Program program = new Parser(new Lexer("""
            class Foo {}
            class Bar {}
            const value = 7;
            func result() i32 { return value; }
            """).getTokens()).parse();
        final BytecodeGenerator generator =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            );
        final var classes = generator.generateClasses();
        assertEquals(
            List.of("Test", "Test$Foo", "Test$Bar"),
            new ArrayList<>(classes.keySet())
        );
        for (final byte[] bytecode : classes.values()) {
            BytecodeUtil.verify(bytecode);
        }
        final ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(final String name)
                throws ClassNotFoundException {
                final byte[] bytecode = classes.get(name);
                if (bytecode == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytecode, 0, bytecode.length);
            }
        };
        final Class<?> module = loader.loadClass("Test");
        assertEquals(7, module.getMethod("result").invoke(null));
        for (final String name : List.of("Test$Foo", "Test$Bar")) {
            final Class<?> type = loader.loadClass(name);
            assertInstanceOf(type, type.getConstructor().newInstance());
            assertEquals(module, type.getDeclaringClass());
            assertEquals(module, type.getNestHost());
            assertTrue(Modifier.isStatic(type.getModifiers()));
        }
        assertEquals(2, module.getDeclaredClasses().length);
        assertThrows(IllegalStateException.class, generator::generate);
        final var again = generator.generateClasses();
        for (final String name : classes.keySet()) {
            assertArrayEquals(classes.get(name), again.get(name));
        }
    }

    @Test
    void generatesFinalInstanceFields() throws Exception {
        final Program program =
            new Parser(new Lexer("class Foo { const field = 10; }").getTokens())
                .parse();
        final var model = new SemanticAnalyzer().analyze(program);
        final var generator = new BytecodeGenerator(program, model);
        final var classes = generator.generateClasses();
        final var node = new ClassNode();
        new ClassReader(classes.get("Test$Foo")).accept(node, 0);
        assertNull(field(node, "field").value);
        final Class<?> type = loadClass(classes, "Test$Foo");
        final Object instance = type.getConstructor().newInstance();
        assertEquals(10, type.getField("field").get(instance));
        assertTrue(Modifier.isFinal(type.getField("field").getModifiers()));
        assertFalse(Modifier.isStatic(type.getField("field").getModifiers()));
    }

    @Test
    void classOutputRetainsSingleClassCompatibility() {
        final Program program =
            new Parser(new Lexer("const value = 7;").getTokens()).parse();
        final BytecodeGenerator generator =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            );
        assertArrayEquals(
            generator.generate(),
            generator.generateClasses().get("Test")
        );
    }

    private static ClassNode inspect(final String source) {
        final Program program =
            new Parser(new Lexer(source).getTokens()).parse();
        final byte[] bytes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generate();
        final var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static FieldNode field(final ClassNode node, final String name) {
        return node.fields.stream()
            .filter(field -> field.name.equals(name))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void foldsDoublesWithTheSameBitsAsRuntimeArithmetic() throws Exception {
        for (final String expression : List.of(
            "-0.5 + 12.34 - 10",
            "(16777216.0 + 1.0) - 16777216.0",
            "16777217 - 16777216.0",
            "(2147483647 + 1) + 0.0",
            "(1.0 / 3.0) * 3.0",
            "5.5 % 2.0",
            "-0.0 * 2.0",
            "1.0 / 0.0"
        )) {
            final String source =
                "const folded = " + expression + ";\nvar evaluated = "
                    + expression + ";";
            final var node = inspect(source);
            final double constant =
                assertInstanceOf(Double.class, field(node, "folded").value);
            assertEquals("D", field(node, "folded").desc);
            assertNull(field(node, "evaluated").value);
            final Class<?> type = compile(source);
            assertEquals(
                Double.doubleToRawLongBits(
                    type.getField("evaluated").getDouble(null)
                ),
                Double.doubleToRawLongBits(constant),
                expression
            );
            assertEquals(
                Double.doubleToRawLongBits(constant),
                Double.doubleToRawLongBits(
                    type.getField("folded").getDouble(null)
                ),
                expression
            );
        }
        final var node = inspect("const result = -0.5 + 12.34 - 10;");
        assertEquals(-0.5 + 12.34 - 10, field(node, "result").value);
        assertTrue(
            node.methods.stream()
                .noneMatch(method -> method.name.equals("<clinit>"))
        );
    }

    @Test
    void emitsConstantValuesWithoutClassInitializer() throws Exception {
        final String source = """
            const integer: i32 = 10;
            const floating = 1.5;
            const widened: f32 = 3;
            const yes = true;
            const no = false;
            const letter = 'x';
            const text = "hello";
            const negative = -2_147_483_648;
            const grouped = (2 + 3) * 4;
            const mixed = 3 + 0.5;
            const predicate = 2 < 3 && !false;
            const joined = "hello " + "world";
            const signedZero = -0.0;
            const overflow = 2147483647 + 1;
            const nanComparison = (0.0 / 0.0) == 0.0;
            """;
        final var node = inspect(source);
        assertEquals(10, field(node, "integer").value);
        assertEquals(1.5, field(node, "floating").value);
        assertEquals(3.0f, field(node, "widened").value);
        assertEquals(1, field(node, "yes").value);
        assertEquals(0, field(node, "no").value);
        assertEquals((int) 'x', field(node, "letter").value);
        assertEquals("hello", field(node, "text").value);
        assertEquals(Integer.MIN_VALUE, field(node, "negative").value);
        assertEquals(20, field(node, "grouped").value);
        assertEquals(3.5, field(node, "mixed").value);
        assertEquals(1, field(node, "predicate").value);
        assertEquals("hello world", field(node, "joined").value);
        assertEquals(-0.0, field(node, "signedZero").value);
        assertEquals(Integer.MIN_VALUE, field(node, "overflow").value);
        assertEquals(0, field(node, "nanComparison").value);
        assertTrue(
            node.methods.stream()
                .noneMatch(method -> method.name.equals("<clinit>"))
        );
        final Class<?> type = compile(source);
        assertEquals(10, type.getField("integer").get(null));
        assertEquals(3.0f, type.getField("widened").get(null));
        assertEquals('x', type.getField("letter").get(null));
        assertEquals(true, type.getField("yes").get(null));
        assertEquals("hello world", type.getField("joined").get(null));
    }

    @Test
    void keepsRuntimeInitializationForNonConstantValues() throws Exception {
        final String source = """
            var state = 0;
            func next() i32 { return state++; }
            const first = next();
            const literal = 10;
            const second = next();
            const values = [1, 2];
            const absent = null;
            """;
        final var node = inspect(source);
        assertEquals(10, field(node, "literal").value);
        for (final String name : List
            .of("state", "first", "second", "values", "absent")) {
            assertNull(field(node, name).value, name);
        }
        final var initializer =
            node.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow();
        final var writes = new ArrayList<String>();
        for (final var instruction : initializer.instructions) {
            if (
                instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
            ) {
                writes.add(field.name);
            }
        }
        assertEquals(
            List.of("state", "first", "second", "values", "absent"),
            writes
        );
        final Class<?> type = compile(source);
        assertEquals(0, type.getField("first").get(null));
        assertEquals(1, type.getField("second").get(null));
        assertEquals(2, type.getField("state").get(null));
        assertTrue(Modifier.isFinal(type.getField("first").getModifiers()));
        assertFalse(Modifier.isFinal(type.getField("state").getModifiers()));
        assertArrayEquals(
            new int[] {1, 2},
            intValues(type.getField("values").get(null))
        );
        assertNull(type.getField("absent").get(null));
    }

    @Test
    void preservesRuntimeDivisionByZero() {
        final String source = "const bad = 1 / 0;";
        assertNull(field(inspect(source), "bad").value);
        final Class<?> type = compile(source);
        final ExceptionInInitializerError error =
            assertThrows(
                ExceptionInInitializerError.class,
                () -> type.getField("bad").get(null)
            );
        assertInstanceOf(ArithmeticException.class, error.getCause());
    }

    @Test
    void preservesNestedLoopTargetsAndShadowedLocals() throws Exception {
        final Class<?> type = compile("""
            func count() i32 {
                var result = 0;
                for (var i = 0; i < 3; i++) {
                    for (var i = 0; i < 4; i++) {
                        if (i == 1) { continue; }
                        if (i == 3) { break; }
                        var old = result++;
                    }
                }
                return result;
            }
            func shadow() i32 {
                var x = 3;
                if (true) { var x = 9; }
                return x;
            }
            """);
        assertEquals(6, type.getMethod("count").invoke(null));
        assertEquals(3, type.getMethod("shadow").invoke(null));
    }

    @Test
    void handlesFloatUpdatesAndIntegerMinimum() throws Exception {
        final Class<?> type = compile("""
            func bump() f32 {
                var value: f32 = 1.5;
                var old = value++;
                var ignored = value--;
                return old + value;
            }
            func minimum() i32 { return -2_147_483_648; }
            """);
        assertEquals(3.0f, type.getMethod("bump").invoke(null));
        assertEquals(Integer.MIN_VALUE, type.getMethod("minimum").invoke(null));
    }

    @Test
    void rejectsInvalidOperandsAndEscapingLocals() {
        for (final String source : List.of(
            "const bad = true + 1;",
            "func nothing() {}\nconst bad = nothing();",
            "func nothing() {}\nconst bad = [nothing()];",
            "func f() i32 { if (false) { var x = 1; } return x; }",
            "for (value : 1) {}",
            "const x = 1;\nconst bad = x++;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
    }

    @Test
    void generationIsRepeatable() {
        final Program program =
            new Parser(
                new Lexer(
                    "var value = 1;\nfunc increment() i32 { return value++; }"
                ).getTokens()
            ).parse();
        final BytecodeGenerator generator =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            );
        assertArrayEquals(generator.generate(), generator.generate());
    }

    private static Class<?> compile(final String source) {
        return compile(new Parser(new Lexer(source).getTokens()).parse());
    }

    private static Class<?> compile(final Program program) {
        final byte[] bytes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generate();
        final StringWriter diagnostics = new StringWriter();
        CheckClassAdapter.verify(
            new ClassReader(bytes),
            false,
            new PrintWriter(diagnostics)
        );
        assertEquals("", diagnostics.toString());
        final Class<?> generated = new ClassLoader() {
            Class<?> define() {
                return defineClass(null, bytes, 0, bytes.length);
            }
        }.define();
        // Force method verification without running potentially infinite top-level code.
        assertNotNull(generated.getDeclaredMethods());
        return generated;
    }

    @Test
    void generatesFunctionsAndNumericConversions() throws Exception {
        final Class<?> type = compile("""
            func sum(a: i32, b: i32) i32 { return a + b; }
            func half(a: f32) f32 { return a / 2; }
            func result() f32 { return half(sum(3, 4)); }
            """);
        assertEquals(
            7,
            type.getMethod("sum", int.class, int.class).invoke(null, 3, 4)
        );
        assertEquals(3.5f, type.getMethod("result").invoke(null));
    }

    @Test
    void generatesGlobalsAndInitializersInOrder() throws Exception {
        final Class<?> type = compile("""
            var start = 3;
            func next() i32 { return start++; }
            const old = next();
            var widened: f32 = start;
            var zero: i32 = 0;
            var text: string = "";
            """);
        assertEquals(3, type.getField("old").get(null));
        assertTrue(Modifier.isFinal(type.getField("old").getModifiers()));
        assertFalse(Modifier.isFinal(type.getField("start").getModifiers()));
        assertEquals(4, type.getField("start").get(null));
        assertEquals(4.0f, type.getField("widened").get(null));
        assertEquals(0, type.getField("zero").get(null));
        assertEquals("", type.getField("text").get(null));
    }

    @Test
    void supportsRecursionAndIndependentLocalScopes() throws Exception {
        final Class<?> type = compile("""
            func factorial(n: i32) i32 {
                if (n <= 1) { return 1; }
                return n * factorial(n - 1);
            }
            func identity(n: i32) i32 { return n; }
            """);
        assertEquals(
            120,
            type.getMethod("factorial", int.class).invoke(null, 5)
        );
        assertEquals(9, type.getMethod("identity", int.class).invoke(null, 9));
    }

    @Test
    void shortCircuitsBooleanOperators() throws Exception {
        final Class<?> type = compile("""
            var counter = 0;
            func tick() bool { return counter++ > 0; }
            func conjunction() bool { return false && tick(); }
            func disjunction() bool { return true || tick(); }
            """);
        assertEquals(false, type.getMethod("conjunction").invoke(null));
        assertEquals(true, type.getMethod("disjunction").invoke(null));
        assertEquals(0, type.getField("counter").get(null));
    }

    @Test
    void generatesLoopsWithCorrectContinueAndBreakTargets() throws Exception {
        final Class<?> type = compile("""
            func counted() i32 {
                var result = 0;
                for (var i = 0; i < 8; i++) {
                    if (i < 2) { continue; }
                    if (i == 5) { break; }
                    var ignored = result++;
                }
                return result;
            }
            func postTest() i32 {
                var i = 0;
                do {
                    var ignored = i++;
                    continue;
                } while (i < 3);
                return i;
            }
            func preTest() i32 {
                var i = 0;
                while (i < 9) {
                    var ignored = i++;
                    if (i < 3) { continue; }
                    break;
                }
                return i;
            }
            """);
        assertEquals(3, type.getMethod("counted").invoke(null));
        assertEquals(3, type.getMethod("postTest").invoke(null));
        assertEquals(3, type.getMethod("preTest").invoke(null));
    }

    @Test
    void preservesExposedForEachIndexAcrossContinueAndNestedLoops()
        throws Exception {
        final Class<?> type =
            compile(
                """
                    func result() i32 {
                        var total = 0;
                        for (value, index : [10, 20, 30]) {
                            if (index == 1) { continue; }
                            for (inner, innerIndex : [1, 2]) {
                                for (var step = 0; step < value + index + inner + innerIndex; step++) { var old = total++; }
                            }
                        }
                        return total;
                    }
                    """
            );
        assertEquals(92, type.getMethod("result").invoke(null));
    }

    @Test
    void rejectsMutationOfExposedForEachIndex() {
        for (final String mutation : List.of("index++", "index--")) {
            final Program program =
                new Parser(
                    new Lexer(
                        "for (value, index : [1]) { var old = " + mutation
                            + "; }"
                    ).getTokens()
                ).parse();
            assertThrows(
                SemanticException.class,
                () -> new SemanticAnalyzer().analyze(program)
            );
        }
    }

    @Test
    void generatesArraysAndIndexedForEach() throws Exception {
        final Class<?> type = compile("""
            const ints = [1, 2, 3];
            const floats = [1.5, 2.5];
            const chars = ['a', 'b'];
            const flags = [true, false];
            const strings = ["a", "b"];
            const nested = [[1], [2]];
            const empty = [];
            func find() i32 {
                for (value, index : ints) {
                    if (value == 3) { return index; }
                }
                return -1;
            }
            """);
        assertArrayEquals(
            new int[] {1, 2, 3},
            intValues(type.getField("ints").get(null))
        );
        final EldDoubleArray floats =
            (EldDoubleArray) type.getField("floats").get(null);
        assertEquals(2, floats.size());
        assertEquals(1.5, floats.get(0));
        assertEquals(2.5, floats.get(1));
        final EldCharArray chars =
            (EldCharArray) type.getField("chars").get(null);
        assertEquals(2, chars.size());
        assertEquals('a', chars.get(0));
        assertEquals('b', chars.get(1));
        final EldBooleanArray flags =
            (EldBooleanArray) type.getField("flags").get(null);
        assertEquals(2, flags.size());
        assertTrue(flags.get(0));
        assertFalse(flags.get(1));
        final EldObjectArray<?> strings =
            (EldObjectArray<?>) type.getField("strings").get(null);
        assertEquals(2, strings.size());
        assertEquals("a", strings.get(0));
        assertEquals("b", strings.get(1));
        assertArrayEquals(
            new int[] {2},
            intValues(
                ((EldObjectArray<?>) type.getField("nested").get(null)).get(1)
            )
        );
        assertEquals(
            0,
            ((EldObjectArray<?>) type.getField("empty").get(null)).size()
        );
        assertEquals(2, type.getMethod("find").invoke(null));
    }

    @Test
    void supportsFunctionValuesAndVoidCalls() throws Exception {
        final Class<?> type = compile("""
            var count = 0;
            func tick() { var old = count++; }
            const callback = tick;
            func run() { callback(); }
            """);
        assertNull(type.getMethod("run").invoke(null));
        assertEquals(1, type.getField("count").get(null));
    }

    @Test
    void handlesFloatNaNComparisons() throws Exception {
        final Class<?> type = compile("""
            func less(a: f32, b: f32) bool { return a < b; }
            func lessEqual(a: f32, b: f32) bool { return a <= b; }
            func greater(a: f32, b: f32) bool { return a > b; }
            func greaterEqual(a: f32, b: f32) bool { return a >= b; }
            func equal(a: f32, b: f32) bool { return a == b; }
            func different(a: f32, b: f32) bool { return a != b; }
            """);
        for (final String name : List
            .of("less", "lessEqual", "greater", "greaterEqual", "equal")) {
            assertEquals(
                false,
                type.getMethod(name, float.class, float.class)
                    .invoke(null, Float.NaN, 1f),
                name
            );
        }
        assertEquals(
            true,
            type.getMethod("different", float.class, float.class)
                .invoke(null, Float.NaN, 1f)
        );
    }

    @Test
    void generatesLiteralsArithmeticAndBranches() throws Exception {
        final Class<?> type = compile("""
            func choose(x: i32) i32 {
                if (x < 0) { return -1; }
                elif (x == 0) { return (2 + 3) * 4 / 2 % 7; }
                else { return +1_000; }
            }
            func same(a: string, b: string) bool { return a == b; }
            func joined() string { return "hello " + "world"; }
            func letter() char { return 'x'; }
            func invert(a: bool) bool { return !a; }
            """);
        assertEquals(-1, type.getMethod("choose", int.class).invoke(null, -5));
        assertEquals(3, type.getMethod("choose", int.class).invoke(null, 0));
        assertEquals(1000, type.getMethod("choose", int.class).invoke(null, 5));
        assertEquals(
            true,
            type.getMethod("same", String.class, String.class)
                .invoke(null, new String("abc"), new String("abc"))
        );
        assertEquals("hello world", type.getMethod("joined").invoke(null));
        assertEquals('x', type.getMethod("letter").invoke(null));
        assertEquals(
            false,
            type.getMethod("invert", boolean.class).invoke(null, true)
        );
    }

    @Test
    void missingReturnFailsExplicitlyAtRuntime() throws Exception {
        final Class<?> type = compile("func missing() i32 {}");
        final InvocationTargetException exception =
            assertThrows(
                InvocationTargetException.class,
                () -> type.getMethod("missing").invoke(null)
            );
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    @Test
    void rejectsInvalidCallsBeforeBytecodeGeneration() {
        assertThrows(
            SemanticException.class,
            () -> compile("var x = 1;\nx();")
        );
        assertThrows(
            SemanticException.class,
            () -> compile("func f(x: i32) {}\nf();")
        );
        assertThrows(
            SemanticException.class,
            () -> compile("func f(x: i32) {}\nf(true);")
        );
    }

    @Test
    void generatesAssignmentAndArrayUpdatesFromAst() throws Exception {
        // These AST nodes exist, but their source syntax is not implemented by the parser yet.
        final Range range = new Range(1, 1, 1, 2);
        final IdentifierDeclaration name =
            new IdentifierDeclaration("values", range);
        final VariableDeclaration values =
            new VariableDeclaration(
                Mutability.VAR,
                name,
                null,
                new ArrayExpression(
                    List.of(new LiteralExpression(LiteralKind.INT, "4", range)),
                    range
                ),
                range
            );
        final SubscriptExpression first =
            new SubscriptExpression(
                new IdentifierExpression("values", range),
                new LiteralExpression(LiteralKind.INT, "0", range),
                range
            );
        final VariableDeclaration old =
            new VariableDeclaration(
                Mutability.CONST,
                new IdentifierDeclaration("old", range),
                null,
                new PostfixExpression(first, PostfixOperator.INCREMENT, range),
                range
            );
        final VariableDeclaration updated =
            new VariableDeclaration(
                Mutability.CONST,
                new IdentifierDeclaration("updated", range),
                null,
                new UnaryExpression(UnaryOperator.INCREMENT, first, range),
                range
            );
        final AssignmentExpression assignment =
            new AssignmentExpression(
                first,
                new LiteralExpression(LiteralKind.INT, "12", range)
            );
        final Program program =
            new Program(
                List.of(
                    values,
                    old,
                    updated,
                    new ExpressionStatement(assignment, range)
                ),
                range
            );
        final Class<?> type = compile(program);
        assertEquals(4, type.getField("old").get(null));
        assertEquals(6, type.getField("updated").get(null));
        assertArrayEquals(
            new int[] {12},
            intValues(type.getField("values").get(null))
        );
    }
}
