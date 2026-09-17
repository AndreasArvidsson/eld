package mylang;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.util.CheckClassAdapter;
import mylang.lexer.Lexer;
import mylang.parser.ArrayExpression;
import mylang.parser.AssignmentExpression;
import mylang.parser.ExpressionStatement;
import mylang.parser.IdentifierDeclaration;
import mylang.parser.IdentifierExpression;
import mylang.parser.IndexExpression;
import mylang.parser.LiteralExpression;
import mylang.parser.LiteralKind;
import mylang.parser.Mutability;
import mylang.parser.Parser;
import mylang.parser.PostfixExpression;
import mylang.parser.PostfixOperator;
import mylang.parser.Program;
import mylang.parser.UnaryExpression;
import mylang.parser.UnaryOperator;
import mylang.parser.VariableDeclaration;
import mylang.semantic.SemanticAnalyzer;
import mylang.semantic.SemanticException;

class BytecodeGeneratorTest {

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
            func next() int { count++; return count; }
            (next());
            next() + 10;
            func result() int { return count; }
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
            "direct\nreference\nnested\n",
            BytecodeRunner.run(classes)
        );
    }

    @Test
    void userFunctionCanShadowBuiltinPrint() throws Exception {
        final Class<?> type = compile("""
            func print(value: int) int { return value + 1; }
            func result() int { return print(41); }
            """);
        assertEquals(42, type.getMethod("result").invoke(null));
    }

    @Test
    void storesInstanceFieldsInReceiverValueOrder() throws Exception {
        final Program program = new Parser(new Lexer("""
            class Counter {
                const initial = 10;
                var count = initial;
                var floating = 1.5;
                var text = "hello";
                func postfix() int { return count++; }
                func next() int { return count++; }
                func decrement() float { return floating--; }
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
            final String source = "func result() int { " + body + " }";
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
            func result() int {
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
            func next() int { return seed++; }
            class Counter {
                const first = next();
                const second = next();
                var count = first;
                var widened: float = count;
                var values = [1, 2];
                var zero: int;
                var text: string;
                func increment() int { return count++; }
                func add(delta: int) int { return count + delta; }
                func shadow(count: int) int { return count; }
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
        assertNull(type.getField("text").get(first));
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
                func step() int { return count++; }
                const initial = step();
                const callback = step;
                func direct() int { return step(); }
                func indirect() int { return callback(); }
                func recursive(n: int) int {
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
                func advance(limit: int) int {
                    for (var i = 0; i < limit; i++) {
                        if (i == 1) { continue; }
                        var ignored = count++;
                    }
                    return count;
                }
                var floating = 1.5;
                func floatStep() float { return floating++; }
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
            func read() int { return value; }
            class First {
                var value = 1;
                func read() int { return value; }
                func call() int { return read(); }
            }
            class Second {
                var value = 2;
                func read() int { return value; }
                func call() int { return read(); }
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
            func result() int { return value; }
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
    void foldsFloatsWithTheSameBitsAsRuntimeArithmetic() throws Exception {
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
            final float constant =
                assertInstanceOf(Float.class, field(node, "folded").value);
            assertEquals("F", field(node, "folded").desc);
            assertNull(field(node, "evaluated").value);
            final Class<?> type = compile(source);
            assertEquals(
                Float.floatToRawIntBits(
                    type.getField("evaluated").getFloat(null)
                ),
                Float.floatToRawIntBits(constant),
                expression
            );
            assertEquals(
                Float.floatToRawIntBits(constant),
                Float.floatToRawIntBits(type.getField("folded").getFloat(null)),
                expression
            );
        }
        final var node = inspect("const result = -0.5 + 12.34 - 10;");
        assertEquals(1.8400002f, field(node, "result").value);
        assertTrue(
            node.methods.stream()
                .noneMatch(method -> method.name.equals("<clinit>"))
        );
    }

    @Test
    void emitsConstantValuesWithoutClassInitializer() throws Exception {
        final String source = """
            const integer: int = 10;
            const floating = 1.5;
            const widened: float = 3;
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
            var state = 0;
            func next() int { return state++; }
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
            (int[]) type.getField("values").get(null)
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
            func count() int {
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
            func shadow() int {
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
            func bump() float {
                var value = 1.5;
                var old = value++;
                var ignored = value--;
                return old + value;
            }
            func minimum() int { return -2_147_483_648; }
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
            "func f() int { if (false) { var x = 1; } return x; }",
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
                    "var value = 1;\nfunc increment() int { return value++; }"
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
            func sum(a: int, b: int) int { return a + b; }
            func half(a: float) float { return a / 2; }
            func result() float { return half(sum(3, 4)); }
            """);
        assertEquals(
            7,
            type.getMethod("sum", int.class, int.class).invoke(null, 3, 4)
        );
        assertEquals(3.5f, type.getMethod("result").invoke(null));
    }

    @Test
    void reliesOnJvmDefaultsForUninitializedStaticFields() throws Exception {
        final String source = """
            var number: int;
            var floating: float;
            var flag: boolean;
            var text: string;
            """;
        assertTrue(
            inspect(source).methods.stream()
                .noneMatch(method -> method.name.equals("<clinit>"))
        );
        final Class<?> type = compile(source);
        assertEquals(0, type.getField("number").get(null));
        assertEquals(0.0f, type.getField("floating").get(null));
        assertEquals(false, type.getField("flag").get(null));
        assertNull(type.getField("text").get(null));
    }

    @Test
    void initializesOnlyExplicitStaticInitializers() {
        final var node = inspect("""
            var untouched: int;
            var explicit = 0;
            var text: string;
            """);
        final var initializer =
            node.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow();
        final var stores = new ArrayList<String>();
        for (final var instruction : initializer.instructions) {
            if (
                instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTSTATIC
            ) {
                stores.add(field.name);
            }
        }
        assertEquals(List.of("explicit"), stores);
    }

    @Test
    void generatesGlobalsAndInitializersInOrder() throws Exception {
        final Class<?> type = compile("""
            var start = 3;
            func next() int { return start++; }
            const old = next();
            var widened: float = start;
            var zero: int;
            var text: string;
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
                if (n <= 1) { return 1; }
                return n * factorial(n - 1);
            }
            func identity(n: int) int { return n; }
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
            func tick() boolean { return counter++ > 0; }
            func conjunction() boolean { return false && tick(); }
            func disjunction() boolean { return true || tick(); }
            """);
        assertEquals(false, type.getMethod("conjunction").invoke(null));
        assertEquals(true, type.getMethod("disjunction").invoke(null));
        assertEquals(0, type.getField("counter").get(null));
    }

    @Test
    void generatesLoopsWithCorrectContinueAndBreakTargets() throws Exception {
        final Class<?> type = compile("""
            func counted() int {
                var result = 0;
                for (var i = 0; i < 8; i++) {
                    if (i < 2) { continue; }
                    if (i == 5) { break; }
                    var ignored = result++;
                }
                return result;
            }
            func postTest() int {
                var i = 0;
                do {
                    var ignored = i++;
                    continue;
                } while (i < 3);
                return i;
            }
            func preTest() int {
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
                    func result() int {
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
            func find() int {
                for (value, index : ints) {
                    if (value == 3) { return index; }
                }
                return -1;
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
            func less(a: float, b: float) boolean { return a < b; }
            func lessEqual(a: float, b: float) boolean { return a <= b; }
            func greater(a: float, b: float) boolean { return a > b; }
            func greaterEqual(a: float, b: float) boolean { return a >= b; }
            func equal(a: float, b: float) boolean { return a == b; }
            func different(a: float, b: float) boolean { return a != b; }
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
                if (x < 0) { return -1; }
                elif (x == 0) { return (2 + 3) * 4 / 2 % 7; }
                else { return +1_000; }
            }
            func same(a: string, b: string) boolean { return a == b; }
            func joined() string { return "hello " + "world"; }
            func letter() char { return 'x'; }
            func invert(a: boolean) boolean { return !a; }
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
        assertThrows(
            SemanticException.class,
            () -> compile("var x = 1;\nx();")
        );
        assertThrows(
            SemanticException.class,
            () -> compile("func f(x: int) {}\nf();")
        );
        assertThrows(
            SemanticException.class,
            () -> compile("func f(x: int) {}\nf(true);")
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
