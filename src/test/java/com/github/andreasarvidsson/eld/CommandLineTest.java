package com.github.andreasarvidsson.eld;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineTest {
    @TempDir
    Path directory;

    @Test
    void replRetainsStateAndDoesNotReplaySideEffects() throws Exception {
        final String output = capture(() -> {
            final ReplSession session = new ReplSession();
            session.evaluate("var count = 1;\nprint(\"once\");");
            session.evaluate(
                "func next() i32 { count = count + 1; return count; }"
            );
            session.evaluate("next();");
            session.evaluate("count;");
            session.evaluate("const callback = next;");
            session.evaluate("callback();");
            session.evaluate("count;");
        });
        assertEquals("once\n2\n2\n3\n3\n", output);
    }

    @Test
    void replSupportsInheritanceAcrossSubmissions() throws Exception {
        final String output = capture(() -> {
            final ReplSession session = new ReplSession();
            session.evaluate(
                """
                    class Base {
                        protected var value: i32;
                        protected constructor(value: i32 = 4) { this.value = value; }
                        public func read() i32 { return this.value; }
                    }
                    """
            );
            session.evaluate("""
                class Child extends Base {
                    public func read() i32 { return this.value + 2; }
                }
                const child = new Child();
                """);
            session.evaluate("const base: Base = child; base.read();");
            session.evaluate(
                """
                    class Other extends Base {}
                    func choose(flag: bool) Base { return flag ? child : new Other(); }
                    choose(false).read();
                    """
            );
        });
        assertEquals("6\n4\n", output);
    }

    @Test
    void replSupportsExplicitSuperAcrossSubmissions() throws Exception {
        final String output = capture(() -> {
            final ReplSession session = new ReplSession();
            session.evaluate("""
                class Base {
                    protected var value: i32;
                    protected constructor(callback: () => i32, extra: i32 = 2) {
                        this.value = callback() + extra;
                    }
                    public func read() i32 { return this.value; }
                }
                """);
            session.evaluate("""
                class Child extends Base {
                    public constructor(value: i32 = 4) { super(() => value); }
                }
                const child = new Child();
                """);
            session.evaluate("child.read(); new Child(7).read();");
        });
        assertEquals("6\n9\n", output);
    }

    @Test
    void replRecoversFromCompileAndRuntimeErrors() throws Exception {
        final String output = capture(() -> {
            final ReplSession session = new ReplSession();
            session.evaluate("var count = 5;");
            assertThrows(
                BaseException.class,
                () -> session.evaluate("unknown;")
            );
            session.evaluate("const answer = 42;");
            assertThrows(
                BaseException.class,
                () -> session.evaluate("answer = 0;")
            );
            session.evaluate("var zero = 0;");
            assertThrows(
                ArithmeticException.class,
                () -> session.evaluate("count / zero;")
            );
            session.evaluate("count + answer;");
        });
        assertEquals("47\n", output);
    }

    @Test
    void compileProducesExecutableJarAndRunExecutesSource() throws Exception {
        final Path source = directory.resolve("hello world.eld");
        Files.writeString(source, """
            print("hello"); const values = [1, 2, 3]; print(values[-2:]);
            const bytes: [i8] = [7]; print(bytes);
            const shorts: [i16] = [7]; print(shorts);
            const longs: [i64] = [9000000000]; print(longs);
            const floats: [f32] = [1.5]; print(floats);
            const doubles: [f64] = [2.5]; print(doubles);
            const flags: [bool] = [true, false]; print(flags);
            const chars: [char] = ['h', 'i']; print(chars);
            const strings = ["foo", "bar"]; print(strings); print(strings[-1]);
            const nulls: [null] = [null, null]; print(nulls[:]);
            var optional: i32 | null = null; print(optional);
            optional = 10; print(optional);
            """);
        final String expected =
            "hello\n[2, 3]\n[7]\n[7]\n[9000000000]\n[1.5]\n[2.5]\n[true, false]\n[h, i]\n[foo, bar]\nbar\n[null, null]\nnull\n10\n";
        assertEquals(
            expected,
            capture(
                () -> assertEquals(
                    0,
                    Main.execute(
                        new String[] {"run", source.toString()},
                        System.out,
                        System.err
                    )
                )
            )
        );
        capture(
            () -> assertEquals(
                0,
                Main.execute(
                    new String[] {"compile", source.toString()},
                    System.out,
                    System.err
                )
            )
        );
        final Process process =
            new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java")
                    .toString(),
                "-jar",
                directory.resolve("hello world.jar").toString()
            ).redirectErrorStream(true).start();
        final String output =
            new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
            );
        assertEquals(0, process.waitFor(), output);
        assertEquals(expected, output.replace("\r\n", "\n"));
    }

    @Test
    void replRetainsRuntimeArraysAcrossSubmissions() throws Exception {
        final String output = capture(() -> {
            final ReplSession session = new ReplSession();
            session.evaluate("const values = [1, 2, 3];");
            session.evaluate("values[-1];");
            session.evaluate("values[-1] = 4;");
            session.evaluate("values[:];");
            session.evaluate("const strings = [\"a\", \"b\"]; ");
            session.evaluate("strings[-1];");
            session.evaluate("strings[-1] = \"c\";");
            session.evaluate("strings[:];");
            session.evaluate("const nulls: [null] = [null, null];");
            session.evaluate("nulls[:];");
        });
        assertEquals("3\n4\n[1, 2, 4]\nb\nc\n[a, c]\n[null, null]\n", output);
    }

    @Test
    void invalidArgumentsAndMissingFilesReturnNonzero() {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (final PrintStream output =
            new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            assertEquals(2, Main.execute(new String[0], output, output));
            assertEquals(
                2,
                Main.execute(new String[] {"repl", "extra"}, output, output)
            );
            assertEquals(
                1,
                Main.execute(
                    new String[] {"run",
                            directory.resolve("missing.eld").toString()},
                    output,
                    output
                )
            );
            assertEquals(
                0,
                Main.execute(new String[] {"--help"}, output, output)
            );
        }
    }

    private static String capture(final Action action) throws Exception {
        final PrintStream previous = System.out;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (final PrintStream output =
            new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            action.run();
        }
        finally {
            System.setOut(previous);
        }
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
