package mylang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import mylang.Range;
import mylang.lexer.Lexer;
import mylang.parser.*;
import mylang.semantic.SemanticAnalyzer;
import mylang.semantic.SemanticException;

class BytecodeGeneratorTest {

    private static org.objectweb.asm.tree.ClassNode inspect(
        final String source
    ) {
        final Program program =
            new Parser(new Lexer(source).getTokens()).parse();
        final byte[] bytes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generate();
        final var node = new org.objectweb.asm.tree.ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static org.objectweb.asm.tree.FieldNode field(
        final org.objectweb.asm.tree.ClassNode node,
        final String name
    ) {
        return node.fields.stream()
            .filter(field -> field.name.equals(name))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void emitsConstantValuesWithoutClassInitializer() throws Exception {
        final String source = """
            const integer: int = 10
            const floating = 1.5
            const widened: float = 3
            const yes = true
            const no = false
            const letter = 'x'
            const text = "hello"
            const negative = -2_147_483_648
            const grouped = (2 + 3) * 4
            const mixed = 3 + 0.5
            const predicate = 2 < 3 && !false
            const joined = "hello " + "world"
            const signedZero = -0.0
            const overflow = 2147483647 + 1
            const nanComparison = (0.0 / 0.0) == 0.0
            """;
        final var node = inspect(source);
        assertEquals(10, field(node, "integer").value);
        assertEquals(1.5f, field(node, "floating").value);
        assertEquals(3.0f, field(node, "widened").value);
        assertEquals(1, field(node, "yes").value);
        assertEquals(0, field(node, "no").value);
        assertEquals((int) 'x', field(node, "letter").value);
        assertEquals("hello", field(node, "text").value);
        assertEquals(Integer.MIN_VALUE, field(node, "negative").value);
        assertEquals(20, field(node, "grouped").value);
        assertEquals(3.5f, field(node, "mixed").value);
        assertEquals(1, field(node, "predicate").value);
        assertEquals("hello world", field(node, "joined").value);
        assertEquals(-0.0f, field(node, "signedZero").value);
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
            var state = 0
            func next() int { return state++ }
            const first = next()
            const literal = 10
            const second = next()
            const values = [1, 2]
            const absent = null
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
        final var writes = new java.util.ArrayList<String>();
        for (final var instruction : initializer.instructions) {
            if (
                instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC
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
            (int[]) type.getField("values").get(null)
        );
        assertNull(type.getField("absent").get(null));
    }

    @Test
    void preservesRuntimeDivisionByZero() {
        final String source = "const bad = 1 / 0";
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
            func count() int {
                var result = 0
                for (var i = 0; i < 3; i++) {
                    for (var i = 0; i < 4; i++) {
                        if (i == 1) { continue }
                        if (i == 3) { break }
                        var old = result++
                    }
                }
                return result
            }
            func shadow() int {
                var x = 3
                if (true) { var x = 9 }
                return x
            }
            """);
        assertEquals(6, type.getMethod("count").invoke(null));
        assertEquals(3, type.getMethod("shadow").invoke(null));
    }

    @Test
    void handlesFloatUpdatesAndIntegerMinimum() throws Exception {
        final Class<?> type = compile("""
            func bump() float {
                var value = 1.5
                var old = value++
                var ignored = value--
                return old + value
            }
            func minimum() int { return -2_147_483_648 }
            """);
        assertEquals(3.0f, type.getMethod("bump").invoke(null));
        assertEquals(Integer.MIN_VALUE, type.getMethod("minimum").invoke(null));
    }

    @Test
    void rejectsInvalidOperandsAndEscapingLocals() {
        for (final String source : List.of(
            "const bad = true + 1",
            "func nothing() {}\nconst bad = nothing()",
            "func nothing() {}\nconst bad = [nothing()]",
            "func f() int { if (false) { var x = 1 } return x }",
            "for (value : 1) {}",
            "const x = 1\nconst bad = x++"
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
                    "var value = 1\nfunc increment() int { return value++ }"
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
            func sum(a: int, b: int) int { return a + b }
            func half(a: float) float { return a / 2 }
            func result() float { return half(sum(3, 4)) }
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
            var start = 3
            func next() int { return start++ }
            const old = next()
            var widened: float = start
            var zero: int
            var text: string
            """);
        assertEquals(3, type.getField("old").get(null));
        assertTrue(Modifier.isFinal(type.getField("old").getModifiers()));
        assertFalse(Modifier.isFinal(type.getField("start").getModifiers()));
        assertEquals(4, type.getField("start").get(null));
        assertEquals(4.0f, type.getField("widened").get(null));
        assertEquals(0, type.getField("zero").get(null));
        assertNull(type.getField("text").get(null));
    }

    @Test
    void supportsRecursionAndIndependentLocalScopes() throws Exception {
        final Class<?> type = compile("""
            func factorial(n: int) int {
                if (n <= 1) { return 1 }
                return n * factorial(n - 1)
            }
            func identity(n: int) int { return n }
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
            var counter = 0
            func tick() boolean { return counter++ > 0 }
            func conjunction() boolean { return false && tick() }
            func disjunction() boolean { return true || tick() }
            """);
        assertEquals(false, type.getMethod("conjunction").invoke(null));
        assertEquals(true, type.getMethod("disjunction").invoke(null));
        assertEquals(0, type.getField("counter").get(null));
    }

    @Test
    void generatesLoopsWithCorrectContinueAndBreakTargets() throws Exception {
        final Class<?> type = compile("""
            func counted() int {
                var result = 0
                for (var i = 0; i < 8; i++) {
                    if (i < 2) { continue }
                    if (i == 5) { break }
                    var ignored = result++
                }
                return result
            }
            func postTest() int {
                var i = 0
                do {
                    var ignored = i++
                    continue
                } while (i < 3)
                return i
            }
            func preTest() int {
                var i = 0
                while (i < 9) {
                    var ignored = i++
                    if (i < 3) { continue }
                    break
                }
                return i
            }
            """);
        assertEquals(3, type.getMethod("counted").invoke(null));
        assertEquals(3, type.getMethod("postTest").invoke(null));
        assertEquals(3, type.getMethod("preTest").invoke(null));
    }

    @Test
    void generatesArraysAndIndexedForEach() throws Exception {
        final Class<?> type = compile("""
            const ints = [1, 2, 3]
            const floats = [1.5, 2.5]
            const chars = ['a', 'b']
            const flags = [true, false]
            const strings = ["a", "b"]
            const nested = [[1], [2]]
            const empty = []
            func find() int {
                for (value, index : ints) {
                    if (value == 3) { return index }
                }
                return -1
            }
            """);
        assertArrayEquals(
            new int[] {1, 2, 3},
            (int[]) type.getField("ints").get(null)
        );
        assertArrayEquals(
            new float[] {1.5f, 2.5f},
            (float[]) type.getField("floats").get(null)
        );
        assertArrayEquals(
            new char[] {'a', 'b'},
            (char[]) type.getField("chars").get(null)
        );
        assertArrayEquals(
            new boolean[] {true, false},
            (boolean[]) type.getField("flags").get(null)
        );
        assertArrayEquals(
            new String[] {"a", "b"},
            (String[]) type.getField("strings").get(null)
        );
        assertArrayEquals(
            new int[] {2},
            ((int[][]) type.getField("nested").get(null))[1]
        );
        assertEquals(0, ((Object[]) type.getField("empty").get(null)).length);
        assertEquals(2, type.getMethod("find").invoke(null));
    }

    @Test
    void supportsFunctionValuesAndVoidCalls() throws Exception {
        final Class<?> type = compile("""
            var count = 0
            func tick() { var old = count++ }
            const callback = tick
            func run() { callback() }
            """);
        assertNull(type.getMethod("run").invoke(null));
        assertEquals(1, type.getField("count").get(null));
    }

    @Test
    void handlesFloatNaNComparisons() throws Exception {
        final Class<?> type = compile("""
            func less(a: float, b: float) boolean { return a < b }
            func lessEqual(a: float, b: float) boolean { return a <= b }
            func greater(a: float, b: float) boolean { return a > b }
            func greaterEqual(a: float, b: float) boolean { return a >= b }
            func equal(a: float, b: float) boolean { return a == b }
            func different(a: float, b: float) boolean { return a != b }
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
            func choose(x: int) int {
                if (x < 0) { return -1 }
                elif (x == 0) { return (2 + 3) * 4 / 2 % 7 }
                else { return +1_000 }
            }
            func same(a: string, b: string) boolean { return a == b }
            func joined() string { return "hello " + "world" }
            func letter() char { return 'x' }
            func invert(a: boolean) boolean { return !a }
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
        final Class<?> type = compile("func missing() int {}");
        final InvocationTargetException exception =
            assertThrows(
                InvocationTargetException.class,
                () -> type.getMethod("missing").invoke(null)
            );
        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    @Test
    void rejectsInvalidCallsBeforeBytecodeGeneration() {
        assertThrows(SemanticException.class, () -> compile("var x = 1\nx()"));
        assertThrows(
            SemanticException.class,
            () -> compile("func f(x: int) {}\nf()")
        );
        assertThrows(
            SemanticException.class,
            () -> compile("func f(x: int) {}\nf(true)")
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
        final IndexExpression first =
            new IndexExpression(
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
                new LiteralExpression(LiteralKind.INT, "12", range),
                range
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
            (int[]) type.getField("values").get(null)
        );
    }
}
