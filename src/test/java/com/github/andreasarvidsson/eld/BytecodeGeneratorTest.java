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
import java.io.PrintStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
    void nullableStringsUseStringPrintOverload() {
        final ClassModel module = inspect("""
            func show(value: string | null) { print(value); }
            """);
        final InvokeInstruction println =
            module.methods()
                .stream()
                .filter(method -> method.methodName().equalsString("show"))
                .flatMap(method -> BytecodeUtil.instructions(method).stream())
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .filter(call -> call.name().equalsString("println"))
                .findFirst()
                .orElseThrow();
        assertEquals("(Ljava/lang/String;)V", println.type().stringValue());
    }

    @Test
    void exceptionsUseJvmHandlersAndPreserveCheckedIdentity() throws Exception {
        final String source = """
            const original: IOException = new IOException("checked");
            func fail() { throw original; }
            func forward() { fail(); }
            func caught() bool {
                try { forward(); }
                catch (e: IOException) { return e == original; }
                return false;
            }
            """;
        final Class<?> module = compileClass(source, "Test");
        assertEquals(
            java.io.IOException.class,
            module.getField("original").getType()
        );
        assertEquals(true, module.getMethod("caught").invoke(null));
        final InvocationTargetException thrown =
            assertThrows(
                InvocationTargetException.class,
                () -> module.getMethod("forward").invoke(null)
            );
        assertSame(module.getField("original").get(null), thrown.getCause());
        final ClassModel model = inspect(source);
        final var fail =
            model.methods()
                .stream()
                .filter(method -> method.methodName().equalsString("fail"))
                .findFirst()
                .orElseThrow();
        assertTrue(
            BytecodeUtil.instructions(fail)
                .stream()
                .anyMatch(instruction -> instruction.opcode() == Opcode.ATHROW)
        );
        assertTrue(
            fail.findAttribute(java.lang.classfile.Attributes.exceptions())
                .isEmpty()
        );
        final var caught =
            model.methods()
                .stream()
                .filter(method -> method.methodName().equalsString("caught"))
                .findFirst()
                .orElseThrow();
        final var handlers = caught.code().orElseThrow().exceptionHandlers();
        assertEquals(1, handlers.size());
        assertEquals(
            "java/io/IOException",
            handlers.getFirst().catchType().orElseThrow().asInternalName()
        );
    }

    @Test
    void finallyPreservesSavedValuesAndOverridesAbruptExits() throws Exception {
        final Class<?> module =
            compileClass(
                """
                    var value: i64 = 7;
                    func saved() i64 { try { return value; } finally { value = 9; } }
                    func overridden() i32 { try { return 1; } finally { return 2; } }
                    func recovered() i32 { try { throw new IOException("old"); } finally { return 3; } }
                    func outerHandler() i32 {
                        try {
                            try { return 1; } finally { throw new IOException("cleanup"); }
                        } catch (e: IOException) { return 4; }
                    }
                    """,
                "Test"
            );
        assertEquals(7L, module.getMethod("saved").invoke(null));
        assertEquals(9L, module.getField("value").get(null));
        assertEquals(2, module.getMethod("overridden").invoke(null));
        assertEquals(3, module.getMethod("recovered").invoke(null));
        assertEquals(4, module.getMethod("outerHandler").invoke(null));
    }

    @Test
    void constructorsTrackThrowCatchAndFinallyInitialization()
        throws Exception {
        final Class<?> module = compileClass("""
            class Resource {
                public const value: i32;
                public constructor() {
                    try { throw new IOException("io"); }
                    catch (e: IOException) { this.value = 7; }
                }
            }
            const resource = new Resource();
            const value = resource.value;
            """, "Test");
        assertEquals(7, module.getField("value").get(null));
        assertThrows(SemanticException.class, () -> compileClass("""
            class Resource {
                public const value: i32;
                public constructor() {
                    try { this.value = 1; }
                    catch (e: IOException) {}
                }
            }
            """, "Test"));
        assertThrows(SemanticException.class, () -> compileClass("""
            class Resource {
                public const value: i32;
                public constructor() {
                    try { this.value = 1; }
                    finally { this.value = 2; }
                }
            }
            """, "Test"));
    }

    @Test
    void regexUsesJavaPatternAndMatcher() throws Exception {
        final Class<?> module =
            compileClass(
                """
                    const regex: Regex = Regex.compile(r"(?<digits>\\d+)");
                    const matcher: Matcher = regex.matcher("abc123def456");
                    const found = matcher.find();
                    const group = matcher.group();
                    const numbered = matcher.group(1);
                    const named = matcher.group("digits");
                    const start = matcher.start("digits");
                    const end = matcher.end(1);
                    const second = matcher.find();
                    const secondGroup = matcher.group();
                    const replaced = matcher.replaceAll("#");
                    const reset: Matcher = matcher.reset("789");
                    const matches = reset.matches();
                    const sameRegex: Regex = matcher.pattern();
                    const staticMatches = Regex.matches(r"\\d+", "42");
                    const flagged: Regex = Regex.compile("abc", 2);
                    const insensitive = flagged.matcher("ABC").matches();
                    const prefix = Regex.compile("abc").matcher("abcdef").lookingAt();
                    const region = Regex.compile(r"\\d+").matcher("x123y").region(1, 4).matches();
                    const match = regex.matcher("12").matches;
                    const boundMatch = match();
                    const patternText = regex.pattern();
                    const flags = flagged.flags();
                    const quoted = Regex.quote("a.b");
                    const quote = Regex.quote;
                    const viaHandle = quote("a.b");
                    """,
                "Test"
            );
        assertEquals(
            java.util.regex.Pattern.class,
            module.getField("regex").getType()
        );
        assertEquals(
            java.util.regex.Matcher.class,
            module.getField("matcher").getType()
        );
        for (final String name : List.of(
            "found",
            "second",
            "matches",
            "staticMatches",
            "insensitive",
            "prefix",
            "region",
            "boundMatch"
        )) {
            assertEquals(true, module.getField(name).get(null), name);
        }
        for (final String name : List.of("group", "numbered", "named")) {
            assertEquals("123", module.getField(name).get(null), name);
        }
        assertEquals(
            "(?<digits>\\d+)",
            module.getField("patternText").get(null)
        );
        assertEquals(2, module.getField("flags").get(null));
        assertEquals(3, module.getField("start").get(null));
        assertEquals(6, module.getField("end").get(null));
        assertEquals("456", module.getField("secondGroup").get(null));
        assertEquals("abc#def#", module.getField("replaced").get(null));
        assertSame(
            module.getField("regex").get(null),
            module.getField("sameRegex").get(null)
        );
        assertEquals("\\Qa.b\\E", module.getField("quoted").get(null));
        assertEquals("\\Qa.b\\E", module.getField("viaHandle").get(null));
    }

    @Test
    void regexReportsInvalidTypesAndPreservesJavaErrors() {
        assertThrows(
            SemanticException.class,
            () -> compileClass("const value = Regex;", "Test")
        );
        assertThrows(
            SemanticException.class,
            () -> compileClass(
                "const value: Regex<i32> = Regex.compile(\"x\");",
                "Test"
            )
        );
        assertThrows(
            SemanticException.class,
            () -> compileClass("const regex = Regex.compile(42);", "Test")
        );
        assertThrows(
            SemanticException.class,
            () -> compileClass(
                "const matcher = Regex.compile(\"x\").matcher(42);",
                "Test"
            )
        );
        final ExceptionInInitializerError error =
            assertThrows(
                ExceptionInInitializerError.class,
                () -> compileClass(
                    "const regex = Regex.compile(\"[\");",
                    "Test"
                ).getField("regex").get(null)
            );
        assertInstanceOf(
            java.util.regex.PatternSyntaxException.class,
            error.getCause()
        );
    }

    @Test
    void nullableInheritanceOnlyCastsObjectStoredUnions() {
        final String source =
            """
                class Base {}
                class Child extends Base {}
                class Sibling extends Base {}
                var child: Child | null = new Child();
                const base: Base | null = child;
                func widen(value: Child | null) Base | null { return value; }
                func plain(value: Child) Base | null { return value; }
                func multi(value: Child | Sibling | null) Base | null { return value; }
                """;
        final Program program =
            new Parser(new Lexer(source).getTokens()).parse();
        final byte[] bytes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generateClasses().get("Test");
        final ClassModel module = ClassFile.of().parse(bytes);
        int casts = 0;
        for (final var method : module.methods()) {
            for (final var instruction : BytecodeUtil.instructions(method)) {
                if (instruction.opcode() == Opcode.CHECKCAST) {
                    assertEquals("multi", method.methodName().stringValue());
                    assertEquals(
                        "Test$Base",
                        ((TypeCheckInstruction) instruction).type()
                            .asInternalName()
                    );
                    casts++;
                }
            }
        }
        assertEquals(1, casts);
    }

    @Test
    void inheritedFieldsCanInitializeSubclassFieldsAfterSuper()
        throws Exception {
        final Class<?> module = compileClass("""
            class Base {
                protected var value = 7;
                protected const fixed = 2;
            }
            class Child extends Base {
                public const own: i32;
                public constructor() {
                    super();
                    this.value++;
                    this.own = this.value + this.fixed;
                }
            }
            func result() i32 { return new Child().own; }
            """, "Test");
        assertEquals(10, module.getMethod("result").invoke(null));
        for (final String body : List.of(
            "this.own = this.value; super();",
            "super(); this.own = this.own;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compileClass(
                    "class Base { protected var value = 7; } class Child extends Base { public const own: i32; public constructor() { "
                        + body + " } }",
                    "Test"
                )
            );
        }
    }

    @Test
    void nullableSubclassUnionsWidenToNullableBaseTypes() throws Exception {
        final Class<?> module =
            compileClass(
                """
                    class Base {}
                    class Child extends Base {}
                    class Sibling extends Base {}
                    func widen(value: Child | null) Base | null { return value; }
                    func accepts(value: Base | null) bool { return value != null; }
                    func result(present: bool) bool {
                        var child: Child | null = null;
                        if (present) { child = new Child(); }
                        const base: Base | null = child;
                        return base == child && widen(child) == base && accepts(child) == present;
                    }
                    func multi(choice: i32) Base | null {
                        var value: Child | Sibling | null = null;
                        if (choice == 1) { value = new Child(); }
                        elif (choice == 2) { value = new Sibling(); }
                        return value;
                    }
                    """,
                "Test"
            );
        assertEquals(
            true,
            module.getMethod("result", boolean.class).invoke(null, true)
        );
        assertEquals(
            true,
            module.getMethod("result", boolean.class).invoke(null, false)
        );
        final Class<?> base = module.getClassLoader().loadClass("Test$Base");
        assertNull(module.getMethod("multi", int.class).invoke(null, 0));
        assertTrue(
            base.isInstance(
                module.getMethod("multi", int.class).invoke(null, 1)
            )
        );
        assertTrue(
            base.isInstance(
                module.getMethod("multi", int.class).invoke(null, 2)
            )
        );
        for (final String source : List.of(
            "class Base {} class Child extends Base {} const base: Base | null = new Base(); const child: Child | null = base;",
            "class Base {} class Other {} const other: Other | null = new Other(); const base: Base | null = other;",
            "const value: i32 | null = 1; const widened: i64 | null = value;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compileClass(source, "Test")
            );
        }
    }

    @Test
    void constructorsAllowPreparationBeforeSuper() throws Exception {
        final Class<?> module =
            compileClass(
                """
                    var order = 0;
                    func next() i32 { order++; return order; }
                    class Base {
                        public var first = next();
                        protected var value: i32;
                        protected constructor(value: i32) { this.value = value; }
                    }
                    class Child extends Base {
                        public var second = next();
                        public const third: i32;
                        public constructor(value: i32) {
                            const before = next();
                            var prepared = value;
                            for (var i = 0; i < 2; i++) { prepared++; }
                            const adjust = () => { prepared++; return prepared; };
                            const result = adjust();
                            super(result);
                            this.third = before;
                        }
                        public func result() i32 { return this.third * 1000 + this.first * 100 + this.second * 10 + this.value; }
                    }
                    func result() i32 { return new Child(4).result(); }
                    """,
                "Test"
            );
        assertEquals(1237, module.getMethod("result").invoke(null));
    }

    @Test
    void constructorsInitializeSuperOnEachBranch() throws Exception {
        final Class<?> module =
            compileClass(
                """
                    class Base {
                        protected var value: i32;
                        protected constructor(value: i32) { this.value = value; }
                    }
                    class Child extends Base {
                        public var initialized = 3;
                        public constructor(flag: bool) {
                            var value = 4;
                            if (flag) { value++; super(value); }
                            else { super(value + 2); }
                        }
                        public func result() i32 { return this.value + this.initialized; }
                    }
                    class Once extends Base {
                        public constructor() { while (true) { super(7); break; } }
                        public func result() i32 { return this.value; }
                    }
                    class OnceFor extends Base {
                        public constructor() { for (; true;) { super(8); break; } }
                        public func result() i32 { return this.value; }
                    }
                    class OnceDo extends Base {
                        public constructor() { do { super(9); } while (false); }
                        public func result() i32 { return this.value; }
                    }
                    func result() i32 {
                        return new Child(true).result() + new Child(false).result() + new Once().result()
                            + new OnceFor().result() + new OnceDo().result();
                    }
                    """,
                "Test"
            );
        assertEquals(41, module.getMethod("result").invoke(null));
    }

    @Test
    void constructorsRejectReceiverUseAndInvalidSuperPaths() {
        final String prefix = """
            class Base { protected constructor(value: i32 = 1) {} }
            class Child extends Base {
                public var value = 1;
                public func read() i32 { return this.value; }
                public constructor(flag: bool) {
            """;
        for (final String body : List.of(
            "this.value; super();",
            "this.value = 2; super();",
            "this.read(); super();",
            "const self = this; super();",
            "const read = () => this.value; super();",
            "if (flag) { return; } super();",
            "if (flag) { super(); }",
            "if (flag) { super(); } super();",
            "while (flag) { super(); }",
            "super(); if (flag) { super(); }",
            "if (flag) { super(); } this.value++;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compileClass(prefix + body + " } }", "Test"),
                body
            );
        }
    }

    @Test
    void superPassesArgumentsBeforeSubclassInitialization() throws Exception {
        final Class<?> module =
            compileClass(
                """
                    var order = 0;
                    func next() i32 { order++; return order; }
                    class Base {
                        protected var value: i64;
                        public var initialized = next();
                        protected constructor(value: i64, extra: i64 = value + 1, optional?: i32) {
                            this.value = value + extra;
                        }
                    }
                    class Child extends Base {
                        public var own = next();
                        public constructor(value: i32 = 4) { super(value); }
                        public func read() i64 { return this.value + this.initialized * 10 + this.own; }
                    }
                    class GrandChild extends Child {
                        public constructor(value: i32) { super(value + 1); }
                    }
                    func result() i64 { return new Child().read(); }
                    func chained() i64 { return new GrandChild(5).read(); }
                    """,
                "Test"
            );
        assertEquals(21L, module.getMethod("result").invoke(null));
        assertEquals(47L, module.getMethod("chained").invoke(null));
    }

    @Test
    void superSupportsExpressionsAndReceiverlessLambdas() throws Exception {
        final Class<?> module =
            compileClass(
                """
                    class Base {
                        protected var value: i32;
                        protected constructor(callback: () => i32) { this.value = callback(); }
                    }
                    class Child extends Base {
                        public constructor(value: i32) { super(() => (() => value + 1)()); }
                        public func read() i32 { return this.value; }
                    }
                    class DefaultChild extends Base {
                        public constructor(callback: () => i32 = () => 9) { super(callback); }
                        public func read() i32 { return this.value; }
                    }
                    func result() i32 { return new Child(6).read() + new DefaultChild().read(); }
                    """,
                "Test"
            );
        assertEquals(16, module.getMethod("result").invoke(null));
    }

    @Test
    void superRejectsInvalidPlacementArgumentsAndReceiverAccess() {
        final String base =
            "class Base { protected constructor(value: i32) {} } ";
        for (final String source : List.of(
            "super();",
            "func f() { super(); }",
            "class Root { public constructor() { super(); } }",
            "class Base {} class Child extends Base { public func f() { super(); } }",
            base + "class Child extends Base { public constructor() { super(); } }",
            base + "class Child extends Base { public constructor() { super(true); } }",
            base + "class Child extends Base { public constructor() { super(1, 2); } }",
            base + "class Child extends Base { public constructor() { if (true) { super(1); } } }",
            base + "class Child extends Base { public constructor() { super(1); super(2); } }",
            base + "class Child extends Base { public var v = 1; public constructor() { super(this.v); } }",
            base + "class Child extends Base { public func f() i32 { return 1; } public constructor() { super((() => this.f())()); } }",
            base + "class Child extends Base { public constructor() { super(1); const f = () => { super(2); }; } }",
            "class Base { constructor(value: i32) {} } class Child extends Base { public constructor() { super(1); } }"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compileClass(source, "Test"),
                source
            );
        }
    }

    @Test
    void classesInheritMembersAndOverrideMethods() throws Exception {
        final String source =
            """
                class Base {
                    protected var value: i32;
                    public const factor = 3;
                    protected constructor(value: i32 = 2) { this.value = value; }
                    protected func helper() i32 { return this.value; }
                    public func compute(amount: i32 = 1) i32 { return this.value + amount; }
                    public func dispatch() i32 { return this.compute(); }
                    public func reference() (i32) => i32 { return this.compute; }
                }
                class Child extends Base {
                    public var own = 4;
                    public func compute(amount: i32 = 5) i32 { return this.helper() + amount + 10; }
                    public func increase() i32 {
                        const update = () => { this.value++; return this.helper(); };
                        return update();
                    }
                }
                class GrandChild extends Child {}
                func read(base: Base) i32 { return base.compute(3); }
                func result() i32 {
                    const child = new GrandChild();
                    var base: Base = child;
                    const inherited = child.reference();
                    return child.increase() + child.compute() + base.dispatch()
                        + inherited(2) + read(child) + child.factor + child.own;
                }
                """;
        final Class<?> module = compileClass(source, "Test");
        assertEquals(77, module.getMethod("result").invoke(null));
        final Class<?> base = module.getClassLoader().loadClass("Test$Base");
        final Class<?> child = module.getClassLoader().loadClass("Test$Child");
        final Class<?> grandchild =
            module.getClassLoader().loadClass("Test$GrandChild");
        assertEquals(base, child.getSuperclass());
        assertEquals(child, grandchild.getSuperclass());
        assertTrue(base.isInstance(grandchild.getConstructor().newInstance()));
    }

    @Test
    void inheritancePreservesInitializationAndDeclaringFieldOwners()
        throws Exception {
        final Class<?> module =
            compileClass(
                """
                    var order = 0;
                    func next() i32 { order++; return order; }
                    class Base {
                        public var value = next();
                        protected const fixed: i32;
                        public constructor() { this.fixed = next(); }
                        public func read() i32 { return this.value + this.fixed; }
                    }
                    class Child extends Base {
                        public var value = next();
                        public const own: i32;
                        public constructor() { this.own = next(); }
                        public func result() i32 { return this.value * 10 + this.read() + this.own; }
                    }
                    func result() i32 { return new Child().result(); }
                    """,
                "Test"
            );
        assertEquals(37, module.getMethod("result").invoke(null));
    }

    @Test
    void inheritanceFindsCommonTypesAcrossBranchesAndContainers()
        throws Exception {
        final Class<?> module =
            compileClass(
                """
                    class Base { public func value() i32 { return 1; } }
                    class Left extends Base { public func value() i32 { return 2; } }
                    class Right extends Base { public func value() i32 { return 3; } }
                    func choose(flag: bool) Base { return flag ? new Left() : new Right(); }
                    func identity() bool {
                        const child = new Left();
                        const base: Base = child;
                        return base == child && child != new Right();
                    }
                    func result() i32 {
                        const inferred = true ? new Left() : new Right();
                        const values: [Base] = [new Left(), new Right()];
                        const pair: (Base, Base) = (new Left(), new Right());
                        const nullable: Base | null = new Left();
                        return inferred.value() + choose(false).value() + values[0].value()
                            + values[1].value() + pair[0].value() + pair[1].value();
                    }
                    """,
                "Test"
            );
        assertEquals(15, module.getMethod("result").invoke(null));
        assertEquals(true, module.getMethod("identity").invoke(null));
    }

    @Test
    void inheritanceRejectsInvalidBasesOverridesAndAccess() {
        for (final String source : List.of(
            "class C extends C {}",
            "class C extends Missing {}",
            "const value = 1; class C extends value {}",
            "func value() {} class C extends value {}",
            "class Base { constructor() {} } class Child extends Base {}",
            "class Base { public constructor(value: i32) {} } class Child extends Base {}",
            "class Base { public func f() i32 { return 1; } } class Child extends Base { func f() i32 { return 2; } }",
            "class Base { public func f() i32 { return 1; } } class Child extends Base { protected func f() i32 { return 2; } }",
            "class Base { protected func f() i32 { return 1; } } class Child extends Base { public func f(value: i32) i32 { return value; } }",
            "class Base { public func f() i32 { return 1; } } class Child extends Base { public var f = 1; }",
            "class Base { var value = 1; } class Child extends Base { public func f() i32 { return this.value; } }",
            "class Base { func f() i32 { return 1; } } class Child extends Base { public func g() i32 { return this.f(); } }",
            "class Base { protected var value = 1; } class Child extends Base {} new Child().value;",
            "class Base { protected func f() {} } class Child extends Base {} new Child().f;",
            "class Base { protected const value = 1; } class Child extends Base { public constructor() { this.value = 2; } }",
            "class Base {} class Child extends Base {} const child: Child = new Base();",
            "class Base {} class Child extends Base {} const children = [new Child()]; const bases: [Base] = children;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compileClass(source, "Test"),
                source
            );
        }
    }

    @Test
    void classMembersArePrivateUnlessPublic() throws Exception {
        final String source =
            """
                class Secret {
                    var value = 4;
                    const factor = 2;
                    public var exposed = 1;
                    public const fixed = 3;
                    public var initialized: i32;
                    public constructor() { this.initialized = 7; }
                    func helper(amount: i32 = 1) i32 { return this.value + amount; }
                    public func read(other: Secret) i32 { return other.value * this.factor + other.helper(); }
                    public func reference() () => i32 { return () => this.helper(); }
                    public func methodReference() (i32) => i32 { return this.helper; }
                    public func defaulted(amount: i32 = 2) i32 { return this.helper(amount); }
                }
                func result() i32 {
                    const secret = new Secret();
                    secret.exposed = 5;
                    return secret.read(secret) + secret.reference()() + secret.methodReference()(3)
                        + secret.defaulted() + secret.exposed + secret.fixed + secret.initialized;
                }
                """;
        final Class<?> module = compileClass(source, "Test");
        assertEquals(46, module.getMethod("result").invoke(null));
        final Class<?> secret =
            module.getClassLoader().loadClass("Test$Secret");
        assertTrue(
            Modifier.isPrivate(secret.getDeclaredField("value").getModifiers())
        );
        assertTrue(
            Modifier.isPrivate(secret.getDeclaredField("factor").getModifiers())
        );
        assertTrue(
            Modifier.isPublic(secret.getDeclaredField("exposed").getModifiers())
        );
        assertTrue(
            Modifier.isPublic(secret.getDeclaredField("fixed").getModifiers())
        );
        assertTrue(
            Modifier.isPrivate(
                secret.getDeclaredMethod("helper", int.class).getModifiers()
            )
        );
        assertTrue(
            Modifier.isPrivate(
                secret.getDeclaredMethod("helper", int.class, boolean[].class)
                    .getModifiers()
            )
        );
        assertTrue(
            Modifier.isPublic(
                secret
                    .getDeclaredMethod("defaulted", int.class, boolean[].class)
                    .getModifiers()
            )
        );
        for (final var method : secret.getDeclaredMethods()) {
            if (method.getName().startsWith("$lambda")) {
                assertTrue(Modifier.isPrivate(method.getModifiers()));
            }
        }
    }

    @Test
    void rejectsPrivateAccessFromOutsideTheDeclaringClass() {
        for (final String expression : List.of(
            "secret.value;",
            "secret.value = 5;",
            "secret.value++;",
            "secret.helper();",
            "secret.helper;",
            "(secret.helper)();",
            "(() => secret.value)();",
            "(() => secret.helper())();"
        )) {
            final String source =
                "class Secret { public constructor() {} var value = 1; func helper() i32 { return this.value; } } "
                    + "const secret = new Secret(); " + expression;
            final SemanticException error =
                assertThrows(
                    SemanticException.class,
                    () -> compileClass(source, "Test"),
                    expression
                );
            assertTrue(
                error.getMessage().contains("is private"),
                error.getMessage()
            );
        }
        assertThrows(
            SemanticException.class,
            () -> compileClass(
                """
                    class Secret { public constructor() {} var value = 1; }
                    class Other { public constructor() {} public func read(secret: Secret) i32 { return secret.value; } }
                    """,
                "Test"
            )
        );
    }

    @Test
    void protectedMembersUseVisibilityChecksAndJvmFlags() throws Exception {
        final String declaration =
            """
                class Secret {
                    protected var value: i32;
                    protected const factor = 2;
                    protected constructor(value: i32 = 7) { this.value = value; }
                    protected func helper(amount: i32 = 1) i32 { return this.value + amount; }
                    public func result(other: Secret) i32 {
                        other.value++;
                        const read = () => other.helper();
                        const ref = other.helper;
                        return read() + ref(2) + new Secret().value + this.factor;
                    }
                }
                """;
        final Class<?> module = compileClass(declaration, "Test");
        final Class<?> secret =
            module.getClassLoader().loadClass("Test$Secret");
        for (final var field : secret.getDeclaredFields()) {
            assertTrue(Modifier.isProtected(field.getModifiers()));
        }
        for (final var constructor : secret.getDeclaredConstructors()) {
            assertTrue(Modifier.isProtected(constructor.getModifiers()));
        }
        for (final var method : secret.getDeclaredMethods()) {
            if (method.getName().equals("helper")) {
                assertTrue(Modifier.isProtected(method.getModifiers()));
            }
        }
        final var constructor = secret.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        final Object instance = constructor.newInstance(3);
        assertEquals(
            20,
            secret.getMethod("result", secret).invoke(instance, instance)
        );
        for (final String expression : List.of(
            "secret.value;",
            "secret.value = 5;",
            "secret.value++;",
            "secret.factor;",
            "secret.helper();",
            "secret.helper;",
            "(() => secret.helper())();"
        )) {
            final SemanticException error =
                assertThrows(
                    SemanticException.class,
                    () -> compileClass(
                        declaration + " func access(secret: Secret) { "
                            + expression + " }",
                        "Test"
                    )
                );
            assertTrue(error.getMessage().contains("is protected"));
        }
        for (final String arguments : List.of("", "1")) {
            final SemanticException error =
                assertThrows(
                    SemanticException.class,
                    () -> compileClass(
                        declaration + " new Secret(" + arguments + ");",
                        "Test"
                    )
                );
            assertTrue(
                error.getMessage()
                    .contains("Constructor of class Secret is protected")
            );
        }
    }

    @Test
    void rejectsPublicOutsideClassMembers() {
        for (final String source : List.of(
            "public var value = 1;",
            "public func f() {}",
            "protected var value = 1;",
            "protected func f() {}",
            "protected constructor() {}",
            "func f() { protected var value = 1; }",
            "class C { public protected var value = 1; }",
            "class C { protected public func f() {} }",
            "class C { protected protected constructor() {} }",
            "func f() { public var value = 1; }",
            "class C { public constructor() {} public public var value = 1; }"
        )) {
            assertThrows(
                com.github.andreasarvidsson.eld.parser.ParserException.class,
                () -> compileClass(source, "Test"),
                source
            );
        }
    }

    @Test
    void constructorsUseMemberVisibility() throws Exception {
        for (final String declaration : List.of(
            "class Secret { constructor() {} }",
            "class Secret { constructor(value: i32 = 1) {} }"
        )) {
            for (final String arguments : List.of("", "1")) {
                final SemanticException error =
                    assertThrows(
                        SemanticException.class,
                        () -> compileClass(
                            declaration + " const value = new Secret("
                                + arguments + ");",
                            "Test"
                        )
                    );
                assertTrue(
                    error.getMessage()
                        .contains("Constructor of class Secret is private")
                );
            }
            final Class<?> module = compileClass(declaration, "Test");
            final Class<?> secret =
                module.getClassLoader().loadClass("Test$Secret");
            for (final var constructor : secret.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
        }
        final Class<?> module =
            compileClass(
                """
                    class Secret {
                        public var value: i32;
                        constructor(value: i32 = 7) { this.value = value; }
                        public func create() Secret { return new Secret(); }
                        public func lambda() () => Secret { return () => new Secret(9); }
                    }
                    class Open {
                        public constructor(value: i32 = 1) {}
                    }
                    const open = new Open();
                    class Implicit {
                        public var value = 5;
                    }
                    func implicitValue() i32 { return new Implicit().value; }
                    """,
                "Test"
            );
        final Class<?> secret =
            module.getClassLoader().loadClass("Test$Secret");
        final var constructor = secret.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        final Object instance = constructor.newInstance(3);
        final Object created = secret.getMethod("create").invoke(instance);
        assertEquals(7, secret.getField("value").get(created));
        final var lambda =
            (java.lang.invoke.MethodHandle) secret.getMethod("lambda")
                .invoke(instance);
        try {
            assertEquals(
                9,
                secret.getField("value").get(lambda.invokeWithArguments())
            );
        }
        catch (Throwable error) {
            throw new AssertionError(error);
        }
        final Class<?> open = module.getClassLoader().loadClass("Test$Open");
        assertEquals(5, module.getMethod("implicitValue").invoke(null));
        final Class<?> implicit =
            module.getClassLoader().loadClass("Test$Implicit");
        assertTrue(
            Modifier.isPublic(implicit.getDeclaredConstructor().getModifiers())
        );
        assertEquals(
            5,
            implicit.getField("value")
                .get(implicit.getConstructor().newInstance())
        );
        for (final var publicConstructor : open.getDeclaredConstructors()) {
            assertTrue(Modifier.isPublic(publicConstructor.getModifiers()));
        }
    }

    @Test
    void capturedPrimitiveUpdatesDoNotBox() throws Exception {
        final String source = """
            func result() i32 {
                var value = 1000;
                const update = () => { value++; return value; };
                value = 2000;
                return update();
            }
            """;
        final Class<?> type = compile(source);
        assertEquals(2001, type.getMethod("result").invoke(null));
        final ClassModel node = inspect(source);
        assertTrue(
            node.methods()
                .stream()
                .anyMatch(
                    method -> method.methodName()
                        .stringValue()
                        .startsWith("$lambda")
                        && method.methodType().stringValue().equals("([I)I")
                )
        );
        for (final var method : node.methods()) {
            for (final var instruction : BytecodeUtil.instructions(method)) {
                if (instruction instanceof InvokeInstruction call) {
                    assertFalse(
                        call.owner()
                            .asInternalName()
                            .equals("java/lang/Integer"),
                        "Captured i32 must not box or unbox"
                    );
                }
            }
        }
    }

    @Test
    void capturedCellsPreserveAllPrimitiveAndReferenceTypes() throws Exception {
        final Class<?> type =
            compile(
                """
                    func small() i8 { var value: i8 = 126; const f = () => { value++; return value; }; return f(); }
                    func shortValue() i16 { var value: i16 = 1000; const f = () => { value++; return value; }; return f(); }
                    func longValue() i64 { var value: i64 = 1000; const f = () => { value++; return value; }; return f(); }
                    func floatValue() f32 { var value: f32 = 1.5; const f = () => { value++; return value; }; return f(); }
                    func doubleValue() f64 { var value: f64 = 1.5; const f = () => { value++; return value; }; return f(); }
                    func booleanValue() bool { var value = true; const f = () => { value = !value; return value; }; return f(); }
                    func character() char { var value = 'a'; const f = () => { value++; return value; }; return f(); }
                    func text() string { var value = "a"; const f = () => { value = "b"; return value; }; return f(); }
                    func nullable() i32 | null { var value: i32 | null = 1000; const f = () => { value = null; return value; }; return f(); }
                    """
            );
        assertEquals((byte) 127, type.getMethod("small").invoke(null));
        assertEquals((short) 1001, type.getMethod("shortValue").invoke(null));
        assertEquals(1001L, type.getMethod("longValue").invoke(null));
        assertEquals(2.5f, type.getMethod("floatValue").invoke(null));
        assertEquals(2.5, type.getMethod("doubleValue").invoke(null));
        assertEquals(false, type.getMethod("booleanValue").invoke(null));
        assertEquals('b', type.getMethod("character").invoke(null));
        assertEquals("b", type.getMethod("text").invoke(null));
        assertNull(type.getMethod("nullable").invoke(null));
    }

    @Test
    void lambdasWithExpressionAndBlockBodies() throws Exception {
        final Class<?> type = compile("""
            const expression = () => 42;
            const block = () => { return 7; };
            const empty = () => {};
            var calls = 0;
            const action = () => { calls++; };
            const nested = () => () => 9;
            func result() i32 {
                empty(); action();
                return expression() + block() + nested()();
            }
            func immediate() i32 { return (() => 3)(); }
            """);
        assertEquals(58, type.getMethod("result").invoke(null));
        assertEquals(1, type.getField("calls").get(null));
        assertEquals(3, type.getMethod("immediate").invoke(null));
    }

    @Test
    void lambdasCaptureLocalsAndShareMutableBindings() throws Exception {
        final Class<?> type = compile("""
            func result() i64 {
                const factor: i64 = 10;
                var count: i64 = 1;
                const next = () => { count++; return count * factor; };
                const read = () => count;
                count = 4;
                return next() + read();
            }
            func nested() i32 {
                var count = 1;
                const outer = () => () => { count++; return count; };
                const inner = outer();
                count = 5;
                return inner();
            }
            func independent() i32 {
                var result = 0;
                for (var i = 0; i < 2; i++) {
                    var value = i;
                    const read = () => value;
                    value++;
                    result = result + read();
                }
                return result;
            }
            """);
        assertEquals(55L, type.getMethod("result").invoke(null));
        assertEquals(6, type.getMethod("nested").invoke(null));
        assertEquals(3, type.getMethod("independent").invoke(null));
    }

    @Test
    void lambdasCaptureTheirReceiver() throws Exception {
        final Class<?> type = compileClass("""
            class Counter {
                public constructor() {}
                public var value = 4;
                public func result() i32 {
                    const next = () => { this.value++; return this.value; };
                    return next();
                }
            }
            func result() i32 { return new Counter().result(); }
            """, "Test");
        assertEquals(5, type.getMethod("result").invoke(null));
    }

    @Test
    void lambdaParametersUseTheExpectedFunctionType() throws Exception {
        final Class<?> type = compile("""
            func add(a: i32, b: i32) i32 { return a + b; }
            func result() i32 {
                const offset = 3;
                var callback = add;
                callback = (a, b) => a * b + offset;
                return callback(4, 5);
            }
            """);
        assertEquals(23, type.getMethod("result").invoke(null));
    }

    @Test
    void typedLambdaDeclarationsAndCallbacks() throws Exception {
        final Class<?> type =
            compile(
                """
                    const foo: (i32, i32) => i32 = (a, b) => a + b;
                    const answer: () => i32 = () => 42;
                    const action: () => = () => {};
                    const block: (i32) => i32 = (a) => { return a * 2; };
                    const nested: () => () => i32 = () => () => 7;
                    func apply(callback: (i32) => i32, value: i32) i32 { return callback(value); }
                    func result() i32 {
                        action();
                        return foo(1, 2) + answer() + block(4) + nested()() + apply((x) => x + 1, 5);
                    }
                    """
            );
        assertEquals(66, type.getMethod("result").invoke(null));
    }

    @Test
    void rejectsIncompatibleTypedLambdas() {
        for (final String source : List.of(
            "const f: (i32) => i32 = () => 1;",
            "const f: () => i32 = () => true;",
            "const f: (i32) => i32 = (x) => { return true; };"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
    }

    @Test
    void constructorLambdasCannotAssignConstFields() {
        for (final String body : List.of(
            "const f = () => { this.value = 2; }; f();",
            "const f = () => this.value = 2; f();",
            "const f = () => () => { this.value = 2; }; f()();"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compileClass(
                    "class C { public const value = 1; public constructor() { "
                        + body + " } }",
                    "Test"
                ),
                body
            );
        }
    }

    @Test
    void constructorLambdasCannotCaptureAnUninitializedReceiver() {
        for (final String body : List.of(
            "const f = () => this.value; print(f()); this.value = 5;",
            "print((() => this.value)()); this.value = 5;",
            "const f = () => () => this.value; this.value = 5;",
            "const f = () => this; this.value = 5;",
            "const f = () => { this.value = 5; }; f();",
            "if (flag) { this.value = 5; } const f = () => this.value; this.value = 5;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compileClass(
                    "class C { public var value: i32; public constructor(flag: bool) { "
                        + body + " } }",
                    "Test"
                ),
                body
            );
        }
    }

    @Test
    void constructorLambdasPreserveDefiniteInitialization() throws Exception {
        final Class<?> type = compileClass("""
            class C {
                public const value: i32;
                public var count: i32;
                public constructor() {
                    const initial = () => { return 5; };
                    this.value = initial();
                    this.count = 0;
                    const read = () => this.value;
                    const update = () => { this.count++; };
                    update();
                    this.count = this.count + read();
                }
            }
            func result() i32 { return new C().count; }
            """, "Test");
        assertEquals(6, type.getMethod("result").invoke(null));
    }

    @Test
    void rejectsInvalidLambdaBodiesAndCalls() {
        for (final String source : List.of(
            "const f = () => missing;",
            "const f = () => { break; };",
            "const f = () => { const x = 1; x = 2; };",
            "const f = () => { if (true) { return 1; } return; };",
            "const f = () => 1; f(2);",
            "const f = (x) => x;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
    }

    @Test
    void optionalAndDefaultArguments() throws Exception {
        final Class<?> type =
            compile(
                """
                    var calls = 0;
                    func next() i32 { calls++; return calls; }
                    func optional(foo?: i32) i32 | null { return foo; }
                    func defaults(a: i64 = next(), b: f64 = a + 2) f64 { return a * 10 + b; }
                    func omitted() i32 | null { return optional(); }
                    func supplied() i32 | null { return optional(7); }
                    func explicitNull() i32 | null { return optional(null); }
                    func first() f64 { return defaults(); }
                    func second() f64 { return defaults(b=5); }
                    func explicit() f64 { return defaults(0, 0); }
                    func grouped() f64 { return (defaults)(b=1, a=2); }
                    func mixed(a?: i32, b: i32 = 4, c: i32) i32 { return b + c; }
                    func skipped() i32 { return mixed(c=3); }
                    func nullable(value?: i32 | string) any { return value; }
                    func nullableValue() any { return nullable("hello"); }
                    """
            );
        assertNull(type.getMethod("omitted").invoke(null));
        assertEquals(7, type.getMethod("supplied").invoke(null));
        assertNull(type.getMethod("explicitNull").invoke(null));
        assertEquals(13.0, type.getMethod("first").invoke(null));
        assertEquals(25.0, type.getMethod("second").invoke(null));
        assertEquals(0.0, type.getMethod("explicit").invoke(null));
        assertEquals(21.0, type.getMethod("grouped").invoke(null));
        assertEquals(2, type.getField("calls").get(null));
        assertEquals(7, type.getMethod("skipped").invoke(null));
        assertEquals("hello", type.getMethod("nullableValue").invoke(null));
    }

    @Test
    void defaultsUseTheMethodReceiverAndConstructorParameters()
        throws Exception {
        final Class<?> type =
            compileClass(
                """
                    class Counter {
                        public var value: i32;
                        public constructor(value: i32 = 5) { this.value = value; }
                        public func add(amount: i32 = this.value) i32 { return this.value + amount; }
                    }
                    class Pair {
                        public var value: i64;
                        public constructor(a: i64 = 3, b: i64 = a + 2) { this.value = a + b; }
                    }
                    func method() i32 { return new Counter().add(); }
                    func supplied() i32 { return new Counter(7).add(2); }
                    func constructorDefaults() i64 { return new Pair().value; }
                    """,
                "Test"
            );
        assertEquals(10, type.getMethod("method").invoke(null));
        assertEquals(9, type.getMethod("supplied").invoke(null));
        assertEquals(8L, type.getMethod("constructorDefaults").invoke(null));
    }

    @Test
    void rejectsInvalidOptionalAndDefaultArguments() {
        for (final String source : List.of(
            "func f(a: i32 = true) {}",
            "func f(a?: i32 = 5) {}",
            "func f(a?: i32 = null) {}",
            "class C { public constructor(a?: i32 = 5) {} }",
            "func f(a: i32 = b, b: i32 = 0) {}",
            "func f(a: i32 = a) {}",
            "func f(a: i32 = 0, b: i32) {} f();",
            "func f(a?: i32) {} f(true);",
            "func f(a: i32 = 0) {} f(null);",
            "func f(a: i32 = 0) {} const alias = f; alias();",
            "class C { public var value = 1; public constructor(a: i32 = this.value) {} }",
            "class C { public constructor(a: i32 = 0, b: i32) {} } new C();"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
    }

    @Test
    void decodesStringControlEscapesExceptInRawStrings() throws Exception {
        final Class<?> module = compileClass("""
            const normal = "\\n\\r\\t\\0\\b\\f\\'";
            const formatted = f"\\n\\r\\t\\0\\b\\f\\'{5}";
            const raw = r"\\n\\r\\t\\0\\b\\f\\'";
            const rawFormatted = rf"\\n\\r\\t\\0\\b\\f\\'{5}";
            const unknown = "\\q";
            const escaped = "\\\\n\\\\r\\\\0";
            const nul = '\\0';
            func runtime() string { return "\\n\\r\\t\\0\\b\\f\\'"; }
            func runtimeNul() char { return '\\0'; }
            """, "Test");
        final String decoded = "\n\r\t\0\b\f'";
        final String literal = "\\n\\r\\t\\0\\b\\f\\'";
        assertEquals(decoded, module.getField("normal").get(null));
        assertEquals(decoded + "5", module.getField("formatted").get(null));
        assertEquals(literal, module.getField("raw").get(null));
        assertEquals(literal + "5", module.getField("rawFormatted").get(null));
        assertEquals("\\q", module.getField("unknown").get(null));
        assertEquals("\\n\\r\\0", module.getField("escaped").get(null));
        assertEquals('\0', module.getField("nul").get(null));
        assertEquals(decoded, module.getMethod("runtime").invoke(null));
        assertEquals('\0', module.getMethod("runtimeNul").invoke(null));
    }

    @Test
    void decodesTabsExceptInRawStrings() throws Exception {
        final Class<?> module = compileClass("""
            const value = 5;
            const normal = "hello \\t{value}";
            const raw = r"hello \\t{value}";
            const formatted = f"hello \\t{value}";
            const rawFormatted = rf"hello \\t{value}";
            const escaped = "hello \\\\t";
            const repeated = f"\\t{value}\\t";
            """, "Test");
        assertEquals("hello \t{value}", module.getField("normal").get(null));
        assertEquals("hello \\t{value}", module.getField("raw").get(null));
        assertEquals("hello \t5", module.getField("formatted").get(null));
        assertEquals("hello \\t5", module.getField("rawFormatted").get(null));
        assertEquals("hello \\t", module.getField("escaped").get(null));
        assertEquals("\t5\t", module.getField("repeated").get(null));
    }

    @Test
    void preservesRawStringContents() throws Exception {
        final Class<?> module = compileClass("""
            const name = "Ada";
            const raw = r"C:\\Users\\Ada\\\\files";
            const empty = r"";
            const quoted = r"say \\"hello\\"";
            const formatted = rf"C:\\Users\\{name}\\\\files {{literal}}";
            const rawQuote = rf"say \\"{name}\\"";
            const reversed = fr"hello\\\\{name}";
            const nested = rf"{f"hello {name}"}\\\\{r"raw\\\\text"}";
            const normal = f"hello\\\\{name}";
            const multiline = r"first
            second";
            const concatenated = r"a\\\\" + r"b\\\\";
            """, "Test");
        assertEquals(
            "C:\\Users\\Ada\\\\files",
            module.getField("raw").get(null)
        );
        assertEquals("", module.getField("empty").get(null));
        assertEquals(
            "say " + '\\' + '"' + "hello" + '\\' + '"',
            module.getField("quoted").get(null)
        );
        assertEquals(
            "C:\\Users\\Ada\\\\files {literal}",
            module.getField("formatted").get(null)
        );
        assertEquals(
            "say " + '\\' + '"' + "Ada" + '\\' + '"',
            module.getField("rawQuote").get(null)
        );
        assertEquals("hello\\\\Ada", module.getField("reversed").get(null));
        assertEquals(
            "hello Ada\\\\raw\\\\text",
            module.getField("nested").get(null)
        );
        assertEquals("hello\\Ada", module.getField("normal").get(null));
        assertEquals("first\nsecond", module.getField("multiline").get(null));
        assertEquals("a\\\\b\\\\", module.getField("concatenated").get(null));
    }

    @Test
    void interpolatesFormatStrings() throws Exception {
        final Class<?> module = compileClass("""
            const value = 42;
            const greeting = f"hello {value}";
            const empty = f"";
            const plain = f"hello";
            const braces = f"{{value}} = {{{value}}}";
            const expression = f"{value + 1}: {true}, {'x'}, {null}, {[1, 2]}";
            const nested = f"outer {f"inner {value}"}";
            const quoted = f"{ "quoted } text" }";
            const multiline = f"hello
            {value}";
            var count = 0;
            func next() i32 { count = count + 1; return count; }
            const ordered = f"{next()}{next()}";
            const conditional = f"{if (true) { yield 7; } else { yield 8; }}";
            const f = 1;
            const r = 2;
            const rf = 3;
            const fr = 4;
            const identifiers = f"{f}{r}{rf}{fr}";
            const small: i8 = 5;
            const short: i16 = 6;
            const large: i64 = 7;
            const single: f32 = 1.5;
            const double: f64 = 2.5;
            const typed = f"{small}, {short}, {large}, {single}, {double}";
            """, "Test");
        assertEquals("hello 42", module.getField("greeting").get(null));
        assertEquals("", module.getField("empty").get(null));
        assertEquals("hello", module.getField("plain").get(null));
        assertEquals("{value} = {42}", module.getField("braces").get(null));
        assertEquals(
            "43: true, x, null, [1, 2]",
            module.getField("expression").get(null)
        );
        assertEquals("outer inner 42", module.getField("nested").get(null));
        assertEquals("quoted } text", module.getField("quoted").get(null));
        assertEquals("hello\n42", module.getField("multiline").get(null));
        assertEquals("12", module.getField("ordered").get(null));
        assertEquals(2, module.getField("count").get(null));
        assertEquals("7", module.getField("conditional").get(null));
        assertEquals("1234", module.getField("identifiers").get(null));
        assertEquals("5, 6, 7, 1.5, 2.5", module.getField("typed").get(null));
    }

    @Test
    void rejectsInvalidFormatStrings() {
        assertThrows(
            com.github.andreasarvidsson.eld.lexer.LexerException.class,
            () -> new Lexer("f\"hello").getTokens()
        );
        assertThrows(
            com.github.andreasarvidsson.eld.lexer.LexerException.class,
            () -> new Lexer("f\"{value").getTokens()
        );
        assertThrows(
            com.github.andreasarvidsson.eld.lexer.LexerException.class,
            () -> new Lexer("f\"}\"").getTokens()
        );
        assertThrows(
            com.github.andreasarvidsson.eld.parser.ParserException.class,
            () -> compileClass("const value = f\"{}\";", "Test")
        );
        assertThrows(
            SemanticException.class,
            () -> compileClass("const value = f\"{missing}\";", "Test")
        );
        assertThrows(
            SemanticException.class,
            () -> compileClass("const value = f\"{print(1)}\";", "Test")
        );
    }

    @Test
    void constructorsInitializeFieldsWithExactSignatures() throws Exception {
        final Class<?> module =
            compileClass(
                """
                    class Foo {
                        public const name: string;
                        public var value: i64;
                        public var ratio: f64;
                        public var values = [1, 2];
                        public constructor(name: string, value: i64, ratio: f64) {
                            this.name = name;
                            this.value = value;
                            this.ratio = ratio;
                        }
                        public func get() i64 { return this.value; }
                        public func direct() i64 { return this.get(); }
                        public func bound() i64 { const getter = this.get; return getter(); }
                    }
                    const foo = new Foo("hello", 9, 2.5);
                    const other = new Foo("other", 10, 3.5);
                    """,
                "Test"
            );
        final Object foo = module.getField("foo").get(null);
        final Object other = module.getField("other").get(null);
        final Class<?> type = foo.getClass();
        assertEquals("hello", type.getField("name").get(foo));
        assertTrue(Modifier.isFinal(type.getField("name").getModifiers()));
        assertEquals(9L, type.getMethod("direct").invoke(foo));
        assertEquals(9L, type.getMethod("bound").invoke(foo));
        assertEquals(2.5, type.getField("ratio").get(foo));
        assertNotSame(
            type.getField("values").get(foo),
            type.getField("values").get(other)
        );
        assertNotNull(
            type.getConstructor(String.class, long.class, double.class)
        );
        assertThrows(NoSuchMethodException.class, type::getConstructor);
    }

    @Test
    void constructorsCheckEveryCompletionPath() throws Exception {
        for (final String body : List.of(
            "if (flag) { this.value = 1; } else { this.value = 2; }",
            "if (flag) { this.value = 1; return; } this.value = 2;",
            "switch (flag) { case true { this.value = 1; } else { this.value = 2; } }",
            "do { this.value = 1; } while (false);",
            "while (true) { this.value = 1; break; }",
            "for (;;) { this.value = 1; break; }",
            "this.value = if (flag) { yield 1; } else { yield 2; };",
            "this.value = 1; return; this.value = 2;"
        )) {
            final Class<?> module =
                compileClass(
                    "class Foo { public const value: i32; public constructor(flag: bool) { "
                        + body
                        + " } } const foo = new Foo(true); const other = new Foo(false);",
                    "Test"
                );
            final Object foo = module.getField("foo").get(null);
            assertEquals(1, foo.getClass().getField("value").get(foo), body);
            final Object other = module.getField("other").get(null);
            assertTrue(
                (int) other.getClass().getField("value").get(other) > 0,
                body
            );
        }
        for (final String body : List.of(
            "",
            "if (flag) { this.value = 1; }",
            "if (flag) { return; } this.value = 1;",
            "while (flag) { this.value = 1; }",
            "for (item : [1]) { this.value = item; }",
            "switch (flag) { case true { this.value = 1; } }",
            "this.value = 1; this.value = 2;",
            "if (flag) { this.value = 1; } this.value = 2;",
            "do { this.value = 1; } while (flag);",
            "while (flag) { this.value = 1; continue; } this.value = 2;",
            "for (var i = 0; i < 2; i++) { this.value = i; }",
            "print(this.value); this.value = 1;",
            "this.get(); this.value = 1;",
            "const escaped = this; this.value = 1;",
            "const ignored = if (flag) { if (flag) { yield 1; } this.value = 1; yield 2; } else { this.value = 1; yield 2; };",
            "return 5;"
        )) {
            final String source =
                "class Foo { public const value: i32; public constructor(flag: bool) { "
                    + body
                    + " } public func get() i32 { return this.value; } }";
            assertThrows(
                SemanticException.class,
                () -> new SemanticAnalyzer()
                    .analyze(new Parser(new Lexer(source).getTokens()).parse()),
                body
            );
        }
    }

    @Test
    void instanceStateRequiresExplicitAccessAndSafeDefaults() {
        for (final String source : List.of(
            "this;",
            "class Foo { public constructor() {} public var value = this; }",
            "class Foo { public constructor() {} public var a = 1; public var b = a + 1; }",
            "class Foo { public constructor() {} public var a = 1; public var b = this.a + 1; }",
            "class Foo { public constructor() {} public var value = 1; public func get() i32 { return value; } }",
            "class Foo { public constructor() {} public func get() i32 { return 1; } public func other() i32 { return get(); } }",
            "class Foo { public constructor() {} public var value: i32; }",
            "class Foo { public const value = 1; public constructor() { this.value = 2; } }",
            "class Foo { public constructor(value: i32) { value = 2; } }",
            "class Foo { public constructor() {} public constructor(value: i32) {} }",
            "constructor() {}",
            "class Foo { public constructor() {} public func replace() { this = new Foo(); } }"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compileClass(source, "Test"),
                source
            );
        }
        for (final String source : List.of(
            "class Foo { public constructor() {} print(1); }",
            "class Foo { public constructor() {} public var value; }",
            "class Foo { public constructor() void {} }",
            "class Foo { public constructor() {} Foo(value: i32) {} }"
        )) {
            assertThrows(
                com.github.andreasarvidsson.eld.parser.ParserException.class,
                () -> new Parser(new Lexer(source).getTokens()).parse(),
                source
            );
        }
    }

    @Test
    void concreteClassDescriptorsAndDirectCallsRemainPrecise()
        throws Exception {
        final String source =
            """
                class Foo {
                    public constructor() {}
                    public var value = 5;
                    public var other: Foo | null = null;
                    public func getValue() i32 { return this.value; }
                    public func identity(value: Foo) Foo { return value; }
                    public func compare(a: i64, b: f64) bool { return a < b; }
                }
                const foo: Foo = new Foo();
                const optional: Foo | null = null;
                const items: [Foo] = [foo];
                const pair = (foo, true);
                const getter = foo.getValue;
                const bound = getter();
                const exact = foo.compare(b=2.5, a=1);
                const same = foo.identity(foo) == foo;
                func use(value: Foo) i32 { value.value = 6; value.value++; return value.getValue(); }
                func identity(value: Foo) Foo { return value; }
                func nullable() Foo | null { return null; }
                func fromArray() i32 { return items[0].getValue(); }
                func fromTuple() i32 { return pair[0].getValue(); }
                func value() string { return "hello"; }
                func direct() string { return value(); }
                func indirect() string { const callback = value; return callback(); }
                """;
        final Program program =
            new Parser(new Lexer(source).getTokens()).parse();
        final var model = new SemanticAnalyzer().analyze(program);
        final var classes =
            new BytecodeGenerator(program, model).generateClasses();
        final Class<?> module = loadClass(classes, "Test");
        final Object foo = module.getField("foo").get(null);
        final Class<?> fooClass = foo.getClass();
        assertEquals(fooClass, module.getField("foo").getType());
        assertEquals(fooClass, module.getField("optional").getType());
        assertEquals(fooClass, fooClass.getField("other").getType());
        assertEquals(
            fooClass,
            module.getMethod("identity", fooClass).getReturnType()
        );
        assertEquals(fooClass, module.getMethod("nullable").getReturnType());
        assertNull(module.getMethod("nullable").invoke(null));
        assertEquals(5, module.getField("bound").get(null));
        assertEquals(true, module.getField("exact").get(null));
        assertEquals(true, module.getField("same").get(null));
        assertEquals(7, module.getMethod("use", fooClass).invoke(null, foo));
        assertEquals(7, module.getMethod("fromArray").invoke(null));
        assertEquals(7, module.getMethod("fromTuple").invoke(null));
        assertEquals("hello", module.getMethod("direct").invoke(null));
        assertEquals("hello", module.getMethod("indirect").invoke(null));
        assertTrue(model.toString().contains("const foo: Foo"));
        assertTrue(model.toString().contains("class Foo"));
        assertFalse(model.toString().contains("ClassType("));
        final ClassModel node = ClassFile.of().parse(classes.get("Test"));
        final var use =
            node.methods()
                .stream()
                .filter(
                    method -> method.methodName().stringValue().equals("use")
                )
                .findFirst()
                .orElseThrow();
        assertEquals("(LTest$Foo;)I", use.methodType().stringValue());
        for (final var instruction : BytecodeUtil.instructions(use)) {
            assertNotEquals(Opcode.CHECKCAST, instruction.opcode());
            if (instruction instanceof InvokeInstruction call) {
                assertEquals("Test$Foo", call.owner().asInternalName());
                assertEquals("getValue", call.name().stringValue());
                assertEquals(Opcode.INVOKEVIRTUAL, call.opcode());
                assertEquals("()I", call.type().stringValue());
            }
        }
        final var clinit =
            node.methods()
                .stream()
                .filter(
                    method -> method.methodName()
                        .stringValue()
                        .equals("<clinit>")
                )
                .findFirst()
                .orElseThrow();
        final var calls =
            BytecodeUtil.instructions(clinit)
                .stream()
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .toList();
        assertTrue(
            calls.stream()
                .anyMatch(
                    call -> call.owner().asInternalName().equals("Test$Foo")
                        && call.name().stringValue().equals("compare")
                        && call.type().stringValue().equals("(JD)Z")
                        && call.opcode() == Opcode.INVOKEVIRTUAL
                )
        );
        assertTrue(
            calls.stream()
                .anyMatch(
                    call -> call.owner().asInternalName().equals("Test$Foo")
                        && call.name().stringValue().equals("identity")
                        && call.type()
                            .stringValue()
                            .equals("(LTest$Foo;)LTest$Foo;")
                )
        );
        assertTrue(
            calls.stream()
                .anyMatch(
                    call -> call.owner()
                        .asInternalName()
                        .equals("java/lang/invoke/MethodHandle")
                        && call.name().stringValue().equals("bindTo")
                )
        );
        assertTrue(
            calls.stream()
                .anyMatch(
                    call -> call.owner()
                        .asInternalName()
                        .equals("java/lang/invoke/MethodHandle")
                        && call.name().stringValue().equals("invokeExact")
                )
        );
        final var direct =
            node.methods()
                .stream()
                .filter(
                    method -> method.methodName().stringValue().equals("direct")
                )
                .findFirst()
                .orElseThrow();
        final var directCall =
            BytecodeUtil.instructions(direct)
                .stream()
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(Opcode.INVOKESTATIC, directCall.opcode());
        assertEquals("Test", directCall.owner().asInternalName());
        assertEquals("value", directCall.name().stringValue());
        final ClassModel classNode =
            ClassFile.of().parse(classes.get("Test$Foo"));
        final var getValue =
            classNode.methods()
                .stream()
                .filter(
                    method -> method.methodName()
                        .stringValue()
                        .equals("getValue")
                )
                .findFirst()
                .orElseThrow();
        assertEquals(
            Opcode.ALOAD_0,
            BytecodeUtil.instructions(getValue).getFirst().opcode()
        );
        assertEquals(
            Opcode.GETFIELD,
            BytecodeUtil.instructions(getValue).get(1).opcode()
        );
    }

    @Test
    void classMembersSupportMutationCallsAndBoundMethodReferences()
        throws Exception {
        final Class<?> module =
            compileClass(
                """
                    class Foo {
                        public constructor() {}
                        public var value: i64 = 5;
                        public const fixed = 10;
                        public func add(amount: i64) i64 { this.value = this.value + amount; return this.value; }
                    }
                    const foo = new Foo();
                    const before = foo.value++;
                    const assigned = foo.value = 8;
                    const result = foo.add(amount=2);
                    const callback = foo.add;
                    const bound = callback(3);
                    const fresh = new Foo().value;
                    const values = [foo];
                    const indexed = values[0].value;
                    """,
                "Test"
            );
        assertEquals(5L, module.getField("before").get(null));
        assertEquals(8L, module.getField("assigned").get(null));
        assertEquals(10L, module.getField("result").get(null));
        assertEquals(13L, module.getField("bound").get(null));
        assertEquals(5L, module.getField("fresh").get(null));
        assertEquals(13L, module.getField("indexed").get(null));
    }

    @Test
    void classMembersRejectUnknownMembersAndConstantWrites() {
        for (final String source : List.of(
            "class Foo { public constructor() {} public const value = 1; } const foo = new Foo(); foo.value = 2;",
            "class Foo { public constructor() {} } const foo = new Foo(); foo.missing;",
            "const foo = 1; foo.value;",
            "const global = 1; class Foo { public constructor() {} } const foo = new Foo(); foo.global;"
        )) {
            final Program program =
                new Parser(new Lexer(source).getTokens()).parse();
            assertThrows(
                SemanticException.class,
                () -> new SemanticAnalyzer().analyze(program),
                source
            );
        }
    }

    @Test
    void newConstructsIndependentClassInstances() throws Exception {
        final Class<?> module = compileClass("""
            var seed = 5;
            class Foo {
                public constructor() {}
                public var value = seed++;
                public func getValue() i32 { return this.value; }
            }
            const first = new Foo();
            const second = new Foo();
            const same = first == first;
            const different = first != second;
            const values = [new Foo(), new Foo()];
            func make() any { return new Foo(); }
            func choose(flag: bool) any { return flag ? new Foo() : "other"; }
            """, "Test");
        final Object first = module.getField("first").get(null);
        final Object second = module.getField("second").get(null);
        assertEquals("Test$Foo", first.getClass().getName());
        assertEquals(5, first.getClass().getMethod("getValue").invoke(first));
        assertEquals(6, second.getClass().getMethod("getValue").invoke(second));
        assertNotSame(first, second);
        assertEquals(
            first.getClass(),
            module.getMethod("choose", boolean.class)
                .invoke(null, true)
                .getClass()
        );
        assertEquals(
            "other",
            module.getMethod("choose", boolean.class).invoke(null, false)
        );
        assertEquals(true, module.getField("same").get(null));
        assertEquals(true, module.getField("different").get(null));
        final var values = (EldObjectArray) module.getField("values").get(null);
        assertEquals(
            7,
            values.get(0).getClass().getMethod("getValue").invoke(values.get(0))
        );
        assertEquals(
            8,
            values.get(1).getClass().getMethod("getValue").invoke(values.get(1))
        );
        assertEquals(
            first.getClass(),
            module.getMethod("make").invoke(null).getClass()
        );
    }

    @Test
    void newRejectsUndefinedNamesNonClassesAndConstructorArguments() {
        for (final String source : List.of(
            "const value = new Missing();",
            "const Foo = 1; const value = new Foo();",
            "class Foo { public constructor() {} } const value = new Foo(1);",
            "class Foo { public constructor() {} } const value = new Foo(value=1);"
        )) {
            final Program program =
                new Parser(new Lexer(source).getTokens()).parse();
            assertThrows(
                SemanticException.class,
                () -> new SemanticAnalyzer().analyze(program),
                source
            );
        }
    }

    @Test
    void newUsesClassesFromPreviousReplSubmissions() throws Exception {
        final var output = new java.io.ByteArrayOutputStream();
        final var previousOut = System.out;
        try (final var capture = new PrintStream(output)) {
            System.setOut(capture);
            final ReplSession session = new ReplSession();
            session.evaluate(
                "class Foo { public var value: i32; public constructor(value: i32) { this.value = value; } }"
            );
            session.evaluate("const first = new Foo(5);");
            session.evaluate("const second = new Foo(5);");
            session.evaluate(
                "const initial = first.value; first.value++; second.value = 9;"
            );
            session.evaluate("const different = first != second;");
            session.evaluate("func make() any { return new Foo(5); }");
            session.evaluate("const third = make();");
        }
        finally {
            System.setOut(previousOut);
        }
        assertEquals(
            "5\n9\n",
            output.toString(Charset.defaultCharset()).replace("\r\n", "\n")
        );
    }

    @Test
    void anyTargetsConvertIfAndSwitchBranchesIndividually() throws Exception {
        final Class<?> type = compile("""
            func unionConditional(flag: bool) i32 | string {
                return if (flag) { yield 5; } else { yield "hello"; };
            }
            func unionSelection(flag: bool) i64 | string {
                return switch (flag) { case true => 5 else => "hello" };
            }
            func nullableConditional(flag: bool) i32 | null {
                return if (flag) { yield 5; } else { yield null; };
            }
            func conditional(flag: bool) any {
                return if (flag) { yield 5; } else { yield 0.5; };
            }
            func nested(flag: bool) any {
                const values: [any] = [if (flag) {
                    yield if (true) { yield 5; } else { yield "unused"; };
                } else { yield "other"; }];
                return values[0];
            }
            func selection(flag: bool) any {
                return switch (flag) { case true => 5 else { yield 0.5; } };
            }
            func grouped(flag: bool) any {
                return (if (flag) { yield null; } else { yield true; });
            }
            func multiple(flag: bool) any {
                return if (flag) {
                    if (flag) { yield 5; }
                    yield "fallback";
                } elif (false) { yield false; } else { yield 0.5; };
            }
            """);
        assertEquals(
            5,
            type.getMethod("unionConditional", boolean.class).invoke(null, true)
        );
        assertEquals(
            "hello",
            type.getMethod("unionConditional", boolean.class)
                .invoke(null, false)
        );
        assertEquals(
            5L,
            type.getMethod("unionSelection", boolean.class).invoke(null, true)
        );
        assertEquals(
            "hello",
            type.getMethod("unionSelection", boolean.class).invoke(null, false)
        );
        assertEquals(
            5,
            type.getMethod("nullableConditional", boolean.class)
                .invoke(null, true)
        );
        assertNull(
            type.getMethod("nullableConditional", boolean.class)
                .invoke(null, false)
        );
        assertEquals(
            5,
            type.getMethod("conditional", boolean.class).invoke(null, true)
        );
        assertEquals(
            0.5,
            type.getMethod("conditional", boolean.class).invoke(null, false)
        );
        assertEquals(
            5,
            type.getMethod("nested", boolean.class).invoke(null, true)
        );
        assertEquals(
            "other",
            type.getMethod("nested", boolean.class).invoke(null, false)
        );
        assertEquals(
            5,
            type.getMethod("selection", boolean.class).invoke(null, true)
        );
        assertEquals(
            0.5,
            type.getMethod("selection", boolean.class).invoke(null, false)
        );
        assertNull(type.getMethod("grouped", boolean.class).invoke(null, true));
        assertEquals(
            true,
            type.getMethod("grouped", boolean.class).invoke(null, false)
        );
        assertEquals(
            5,
            type.getMethod("multiple", boolean.class).invoke(null, true)
        );
        assertEquals(
            0.5,
            type.getMethod("multiple", boolean.class).invoke(null, false)
        );
    }

    @Test
    void anyBranchTargetsStillRequireValuesAndCompleteBranches() {
        for (final String source : List.of(
            "const value = if (true) { yield 5; } else { yield 0.5; };",
            "const value: any = if (true) { yield 5; };",
            "const value: any = if (true) { yield 5; } else {};",
            "func empty() {} const value: any = if (true) { yield empty(); } else { yield 1; };",
            "const value: any = switch (1) { case 1 => 5 };",
            "const value: i32 | string = if (true) { yield false; } else { yield 1; };"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
    }

    @Test
    void anyUsesObjectAndBoxesPrimitiveValues() throws Exception {
        final Class<?> type =
            compile(
                """
                    const integer: any = 42;
                    const text: any = "hello";
                    const empty: any = null;
                    func identity(value: any) any { return value; }
                    func number() any { return identity(9000000000); }
                    func primitives() (any, any, any, any, any, any, any, any) {
                        const b: i8 = 1; const s: i16 = 2; const i: i32 = 3;
                        const l: i64 = 4; const f: f32 = 1.5; const d: f64 = 2.5;
                        return (b, s, i, l, f, d, true, 'x');
                    }
                    func union() any { const value: i32 | null = 7; return value; }
                    func changed() any { var value: any = 1; value = "changed"; return value; }
                    func collapsed() any | string | null { return true; }
                    """
            );
        assertEquals(Object.class, type.getField("integer").getType());
        assertEquals(42, type.getField("integer").get(null));
        assertEquals("hello", type.getField("text").get(null));
        assertNull(type.getField("empty").get(null));
        assertEquals(
            Object.class,
            type.getMethod("identity", Object.class).getReturnType()
        );
        assertEquals(9000000000L, type.getMethod("number").invoke(null));
        final var tuple =
            (com.github.andreasarvidsson.eld.runtime.EldTuple) type
                .getMethod("primitives")
                .invoke(null);
        assertEquals(
            List.of((byte) 1, (short) 2, 3, 4L, 1.5f, 2.5, true, 'x'),
            java.util.stream.IntStream.range(0, 8).mapToObj(tuple::get).toList()
        );
        assertEquals(7, type.getMethod("union").invoke(null));
        assertEquals("changed", type.getMethod("changed").invoke(null));
        assertEquals(Object.class, type.getMethod("collapsed").getReturnType());
        assertEquals(true, type.getMethod("collapsed").invoke(null));
    }

    @Test
    void anySupportsCollectionsEqualityAndBranches() throws Exception {
        final Class<?> type =
            compile(
                """
                    func values() [any] {
                        var values: [any] = [1, "two", true, null, (3, false), [4]];
                        values[0] = 5.0;
                        return values;
                    }
                    func same() bool { const value: any = 1; return value == 1; }
                    func reverse() bool { const value: any = 1; return 1 == value; }
                    func different() bool { const value: any = 1; return value != "1"; }
                    func empty() bool { const value: any = null; return value == null; }
                    func tuple() bool { const value: any = (1, true); return value == (1, true); }
                    func branch(flag: bool) any { return flag ? 2 : "two"; }
                    func nested() [[any]] { return [[1, "two"], [null, false]]; }
                    func selection() string { const value: any = 1; return switch (value) { case 1 => "one" else => "other" }; }
                    """
            );
        final var values =
            (EldObjectArray) type.getMethod("values").invoke(null);
        assertEquals(5.0, values.get(0));
        assertEquals("two", values.get(1));
        assertEquals(true, values.get(2));
        assertNull(values.get(3));
        assertEquals("(3, false)", values.get(4).toString());
        assertEquals(true, type.getMethod("same").invoke(null));
        assertEquals(true, type.getMethod("reverse").invoke(null));
        assertEquals(true, type.getMethod("different").invoke(null));
        assertEquals(true, type.getMethod("empty").invoke(null));
        assertEquals(true, type.getMethod("tuple").invoke(null));
        assertEquals(
            2,
            type.getMethod("branch", boolean.class).invoke(null, true)
        );
        assertEquals(
            "two",
            type.getMethod("branch", boolean.class).invoke(null, false)
        );
        final var nested =
            (EldObjectArray) type.getMethod("nested").invoke(null);
        assertEquals("two", ((EldObjectArray) nested.get(0)).get(1));
        assertEquals("one", type.getMethod("selection").invoke(null));
    }

    @Test
    void anyRejectsImplicitNarrowingAndTypeSpecificOperations() {
        for (final String source : List.of(
            "const value: any = 1; const narrow: i32 = value;",
            "const value: any = 1; value + 1;",
            "const value: any = true; if (value) { print(1); }",
            "const value: any = [1]; value[0];",
            "func empty() {} const value: any = empty();",
            "const values: [i32] = [1]; const objects: [any] = values;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
    }

    @Test
    void tupleEqualityComparesValues() throws Exception {
        final Class<?> type =
            compile(
                """
                    func equal() bool { const value = (1, true); return value == (1, true); }
                    func different() bool { return (1, true) == (2, true); }
                    func unequal() bool { return (1, true) != (2, true); }
                    func same() bool { return (1, true) != (1, true); }
                    func nested() bool { return ((1, "hello"), false) == ((1, "hello"), false); }
                    func nullable() bool {
                        const left: (i32 | null, string | null) = (null, null);
                        const right: (i32 | null, string | null) = (null, null);
                        return left == right;
                    }
                    """
            );
        assertEquals(true, type.getMethod("equal").invoke(null));
        assertEquals(false, type.getMethod("different").invoke(null));
        assertEquals(true, type.getMethod("unequal").invoke(null));
        assertEquals(false, type.getMethod("same").invoke(null));
        assertEquals(true, type.getMethod("nested").invoke(null));
        assertEquals(true, type.getMethod("nullable").invoke(null));
    }

    @Test
    void tuplesCompileWithTypedElementsAndNestedAccess() throws Exception {
        final Class<?> type =
            compile(
                """
                    func pair(value: i32) (i64, string) { return (value, "hello"); }
                    func first() i64 { return pair(7)[0]; }
                    func second() string { return pair(7)[1]; }
                    func nested() f64 {
                        const value: (bool, (f64, [i32])) = (true, (2, [3, 4]));
                        return value[1][0] + value[1][1][1];
                    }
                    func tiny() i8 { const value: (i8, f32) = (127, 1.5); return value[0]; }
                    func nullable() i32 | null { const value: (i32 | null, string) = (null, "x"); return value[0]; }
                    func array() string { const values = [(1, "one"), (2, "two")]; return values[1][1]; }
                    func inferred() bool { const value = (true, 'a', 3.0); return value[0]; }
                    func grouped() i64 { const value: (i64, string) = ((3, "x")); return value[0]; }
                    """
            );
        assertEquals(7L, type.getMethod("first").invoke(null));
        assertEquals("hello", type.getMethod("second").invoke(null));
        assertEquals(6.0, type.getMethod("nested").invoke(null));
        assertEquals((byte) 127, type.getMethod("tiny").invoke(null));
        assertNull(type.getMethod("nullable").invoke(null));
        assertEquals("two", type.getMethod("array").invoke(null));
        assertEquals(true, type.getMethod("inferred").invoke(null));
        assertEquals(3L, type.getMethod("grouped").invoke(null));
    }

    @Test
    void tuplesRejectInvalidTypesIndicesAndMutation() {
        for (final String source : List.of(
            "const value: (i32, string) = (1, false);",
            "const value: (i32, i32) = (1, 2, 3);",
            "const value = (1, 2); value[2];",
            "const value = (1, 2); value[-1];",
            "const value = (1, 2); var index = 0; value[index];",
            "const value = (1, 2); value[0] = 3;",
            "const value = (1, 2); value[0]++;"
        )) {
            assertThrows(
                SemanticException.class,
                () -> compile(source),
                source
            );
        }
    }

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
        try (final var capture = new PrintStream(output)) {
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
        final ClassModel node = ClassFile.of().parse(bytes);
        for (final var method : node.methods()) {
            if (
                !List.of("literal", "grouped", "loaded")
                    .contains(method.methodName().stringValue())
            ) {
                continue;
            }
            int casts = 0;
            for (final var instruction : BytecodeUtil.instructions(method)) {
                if (instruction.opcode() == Opcode.CHECKCAST) {
                    casts++;
                }
            }
            assertEquals(
                method.methodName().stringValue().equals("loaded") ? 1 : 0,
                casts,
                method.methodName().stringValue()
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
                public constructor() {}
                public var count: i64 = 1;
                public var real: f64 = 1.25;
                public func old() i64 { return this.count++; }
                public func next() f64 { this.real++; return this.real; }
                public func set() i64 { return this.count = 9000000000; }
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
            node.methods()
                .stream()
                .filter(m -> m.methodName().stringValue().equals("choose"))
                .findFirst()
                .orElseThrow();
        final var table =
            BytecodeUtil.instructions(choose)
                .stream()
                .filter(TableSwitchInstruction.class::isInstance)
                .map(TableSwitchInstruction.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(-1, table.lowValue());
        assertEquals(3, table.highValue());
        assertSame(
            table.defaultTarget(),
            BytecodeUtil.switchTarget(table, table.lowValue() + 2)
        );
        assertSame(
            BytecodeUtil.switchTarget(table, table.lowValue() + 3),
            BytecodeUtil.switchTarget(table, table.lowValue() + 4)
        );
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
            inspect(source).methods()
                .stream()
                .filter(m -> m.methodName().stringValue().equals("choose"))
                .findFirst()
                .orElseThrow();
        final var lookup =
            BytecodeUtil.instructions(choose)
                .stream()
                .filter(LookupSwitchInstruction.class::isInstance)
                .map(LookupSwitchInstruction.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(
            List.of(Integer.MIN_VALUE, -1000, 1000, Integer.MAX_VALUE),
            lookup.cases().stream().map(SwitchCase::caseValue).toList()
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
        for (final var method : inspect(source).methods()) {
            for (final var instruction : BytecodeUtil.instructions(method)) {
                assertFalse(instruction instanceof TableSwitchInstruction);
                assertFalse(instruction instanceof LookupSwitchInstruction);
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
                public constructor() {}
                public const initial = 10;
                public var count = 10;
                public var floating: f32 = 1.5;
                public var text = "hello";
                public func postfix() i32 { return this.count++; }
                public func next() i32 { return this.count++; }
                public func decrement() f32 { return this.floating--; }
            }
            """).getTokens()).parse();
        final var classes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generateClasses();
        final ClassModel node =
            ClassFile.of().parse(classes.get("Test$Counter"));
        for (final var method : node.methods()) {
            for (final var instruction : BytecodeUtil.instructions(method)) {
                assertNotEquals(Opcode.SWAP, instruction.opcode());
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
            for (final var method : node.methods()) {
                for (final var instruction : BytecodeUtil
                    .instructions(method)) {
                    assertNotEquals(Opcode.NOP, instruction.opcode(), body);
                    assertNotEquals(Opcode.ATHROW, instruction.opcode(), body);
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
        for (final var method : inspect(source).methods()) {
            for (final var instruction : BytecodeUtil.instructions(method)) {
                assertNotEquals(Opcode.NOP, instruction.opcode());
                assertNotEquals(Opcode.ATHROW, instruction.opcode());
            }
        }
    }

    private static Class<?> loadClass(
        final Map<String, byte[]> classes,
        final String name
    )
        throws ClassNotFoundException {
        final ModuleLoader loader = new ModuleLoader();
        loader.add(classes);
        for (final byte[] bytecode : classes.values()) {
            BytecodeUtil.verify(bytecode, loader);
        }
        return loader.loadClass(name);
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
        final Class<?> type =
            compileClass(
                """
                    var seed = 2;
                    func next() i32 { return seed++; }
                    class Counter {
                        public const first = next();
                        public const second = next();
                        public var count: i32;
                        public var widened: f32;
                        public var values = [1, 2];
                        public var zero: i32 = 0;
                        public var text: string = "";
                        public constructor() { this.count = this.first; this.widened = this.count; }
                        public func increment() i32 { return this.count++; }
                        public func add(delta: i32) i32 { return this.count + delta; }
                        public func shadow(count: i32) i32 { return count; }
                    }
                    """,
                "Test$Counter"
            );
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
        final Class<?> type =
            compileClass(
                """
                    class Counter {
                        public var count = 10;
                        public func step() i32 { return this.count++; }
                        public var initial = 0;
                        public constructor() { this.initial = this.step(); }

                        public func direct() i32 { return this.step(); }
                        public func indirect() i32 { const callback = this.step; return callback(); }
                        public func recursive(n: i32) i32 {
                            if (n <= 1) { return this.count; }
                            return this.recursive(n - 1) + 1;
                        }
                    }
                    """,
                "Test$Counter"
            );
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
        final Class<?> type =
            compileClass(
                """
                    class Counter {
                        public var count = 0;
                        public constructor() { for (var i = 0; i < 3; i++) { var ignored = this.count++; } }
                        public func advance(limit: i32) i32 {
                            for (var i = 0; i < limit; i++) {
                                if (i == 1) { continue; }
                                var ignored = this.count++;
                            }
                            return this.count;
                        }
                        public var floating: f32 = 1.5;
                        public func floatStep() f32 { return this.floating++; }
                    }
                    """,
                "Test$Counter"
            );
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
                public constructor() {}
                public var value = 1;
                public func read() i32 { return this.value; }
                public func call() i32 { return this.read(); }
            }
            class Second {
                public constructor() {}
                public var value = 2;
                public func read() i32 { return this.value; }
                public func call() i32 { return this.read(); }
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
            class Foo { public constructor() {} }
            class Bar { public constructor() {} }
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
            new Parser(
                new Lexer(
                    "class Foo { public constructor() {} public const field = 10; }"
                ).getTokens()
            ).parse();
        final var model = new SemanticAnalyzer().analyze(program);
        final var generator = new BytecodeGenerator(program, model);
        final var classes = generator.generateClasses();
        final ClassModel node = ClassFile.of().parse(classes.get("Test$Foo"));
        assertNull(BytecodeUtil.constantValue(field(node, "field")));
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

    private static ClassModel inspect(final String source) {
        final Program program =
            new Parser(new Lexer(source).getTokens()).parse();
        final byte[] bytes =
            new BytecodeGenerator(
                program,
                new SemanticAnalyzer().analyze(program)
            ).generate();
        final ClassModel node = ClassFile.of().parse(bytes);
        return node;
    }

    private static FieldModel field(final ClassModel node, final String name) {
        return node.fields()
            .stream()
            .filter(field -> field.fieldName().stringValue().equals(name))
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
                assertInstanceOf(
                    Double.class,
                    BytecodeUtil.constantValue(field(node, "folded"))
                );
            assertEquals("D", field(node, "folded").fieldType().stringValue());
            assertNull(BytecodeUtil.constantValue(field(node, "evaluated")));
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
        assertEquals(
            -0.5 + 12.34 - 10,
            BytecodeUtil.constantValue(field(node, "result"))
        );
        assertTrue(
            node.methods()
                .stream()
                .noneMatch(
                    method -> method.methodName()
                        .stringValue()
                        .equals("<clinit>")
                )
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
        assertEquals(10, BytecodeUtil.constantValue(field(node, "integer")));
        assertEquals(1.5, BytecodeUtil.constantValue(field(node, "floating")));
        assertEquals(3.0f, BytecodeUtil.constantValue(field(node, "widened")));
        assertEquals(1, BytecodeUtil.constantValue(field(node, "yes")));
        assertEquals(0, BytecodeUtil.constantValue(field(node, "no")));
        assertEquals(
            (int) 'x',
            BytecodeUtil.constantValue(field(node, "letter"))
        );
        assertEquals("hello", BytecodeUtil.constantValue(field(node, "text")));
        assertEquals(
            Integer.MIN_VALUE,
            BytecodeUtil.constantValue(field(node, "negative"))
        );
        assertEquals(20, BytecodeUtil.constantValue(field(node, "grouped")));
        assertEquals(3.5, BytecodeUtil.constantValue(field(node, "mixed")));
        assertEquals(1, BytecodeUtil.constantValue(field(node, "predicate")));
        assertEquals(
            "hello world",
            BytecodeUtil.constantValue(field(node, "joined"))
        );
        assertEquals(
            -0.0,
            BytecodeUtil.constantValue(field(node, "signedZero"))
        );
        assertEquals(
            Integer.MIN_VALUE,
            BytecodeUtil.constantValue(field(node, "overflow"))
        );
        assertEquals(
            0,
            BytecodeUtil.constantValue(field(node, "nanComparison"))
        );
        assertTrue(
            node.methods()
                .stream()
                .noneMatch(
                    method -> method.methodName()
                        .stringValue()
                        .equals("<clinit>")
                )
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
        assertEquals(10, BytecodeUtil.constantValue(field(node, "literal")));
        for (final String name : List
            .of("state", "first", "second", "values", "absent")) {
            assertNull(BytecodeUtil.constantValue(field(node, name)), name);
        }
        final var initializer =
            node.methods()
                .stream()
                .filter(
                    method -> method.methodName()
                        .stringValue()
                        .equals("<clinit>")
                )
                .findFirst()
                .orElseThrow();
        final var writes = new ArrayList<String>();
        for (final var instruction : BytecodeUtil.instructions(initializer)) {
            if (
                instruction instanceof FieldInstruction field
                    && field.opcode() == Opcode.PUTSTATIC
            ) {
                writes.add(field.name().stringValue());
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
        assertNull(BytecodeUtil.constantValue(field(inspect(source), "bad")));
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
        BytecodeUtil.verify(bytes);
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
