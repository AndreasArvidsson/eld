package com.github.andreasarvidsson.eld;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import com.github.andreasarvidsson.eld.runtime.RuntimeAbi;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import picocli.CommandLine;

public final class Main {
    private Main() {}

    public static void main(final String[] args) {
        System.exit(execute(args, System.out, System.err));
    }

    static int execute(
        final String[] args,
        final PrintStream out,
        final PrintStream err
    ) {
        final CommandLine command =
            new CommandLine(new Commands(out))
                .setOut(new PrintWriter(out, true, out.charset()))
                .setErr(new PrintWriter(err, true, err.charset()))
                .setExecutionExceptionHandler((error, cli, result) -> {
                    cli.getErr().println("Error: " + diagnostic(error));
                    return 1;
                });

        try {
            return command.execute(args);
        }
        catch (final LinkageError error) {
            err.println("Error: " + diagnostic(error));
            return 1;
        }
    }

    @CommandLine.Command(
        name = "bin", mixinStandardHelpOptions = true,
        description = "Compile and run " + Constants.LANGUAGE_NAME
            + " programs, or start an interactive session."
    )
    static final class Commands {
        private final PrintStream out;

        Commands(final PrintStream out) {
            this.out = out;
        }

        @CommandLine.Command(
            name = "compile", mixinStandardHelpOptions = true,
            description = "Compile a source file to an executable JAR beside it."
        )
        void compile(
            @CommandLine.Parameters(
                index = "0",
                paramLabel = "<file." + Constants.FILE_EXTENSION + ">"
            ) final Path source
        )
            throws IOException {
            final Map<String, byte[]> classes = Compiler.compile(source);
            final String filename = source.getFileName().toString();
            final int dot = filename.lastIndexOf('.');
            final Path output =
                source.resolveSibling(
                    (dot > 0 ? filename.substring(0, dot) : filename) + ".jar"
                );
            if (
                output.toAbsolutePath()
                    .normalize()
                    .equals(source.toAbsolutePath().normalize())
            ) {
                throw new IOException("Output would overwrite the source file");
            }
            writeJar(output, classes);
            out.println(output);
        }

        @CommandLine.Command(
            name = "run", mixinStandardHelpOptions = true,
            description = "Compile and execute a source file in memory."
        )
        void run(
            @CommandLine.Parameters(
                index = "0",
                paramLabel = "<file." + Constants.FILE_EXTENSION + ">"
            ) final Path source
        )
            throws IOException,
            ClassNotFoundException {
            final ModuleLoader loader = new ModuleLoader();
            loader.add(Compiler.compile(source));
            Class.forName("Test", true, loader);
        }

        @CommandLine.Command(
            name = "repl", mixinStandardHelpOptions = true,
            description = "Start the interactive REPL."
        )
        void repl() throws IOException {
            Repl.run();
        }
    }

    static String diagnostic(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = Objects.requireNonNull(cause.getCause());
        }
        return cause.getMessage() == null
            ? cause.toString()
            : Objects.requireNonNull(cause.getMessage());
    }

    private static void writeJar(
        final Path output,
        final Map<String, byte[]> classes
    )
        throws IOException {
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes()
            .put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes()
            .put(Attributes.Name.MAIN_CLASS, "Launcher");
        try (final JarOutputStream jar =
            new JarOutputStream(Files.newOutputStream(output), manifest)) {
            for (final Entry<String, byte[]> entry : classes.entrySet()) {
                jar.putNextEntry(
                    new JarEntry(entry.getKey().replace('.', '/') + ".class")
                );
                jar.write(entry.getValue());
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("Launcher.class"));
            jar.write(launcher());
            jar.closeEntry();
            for (final Class<?> runtimeClass : RuntimeAbi.runtimeClasses()) {
                final String runtimePath =
                    runtimeClass.getName().replace('.', '/') + ".class";
                try (final InputStream runtime =
                    runtimeClass.getResourceAsStream("/" + runtimePath)) {
                    if (runtime == null) {
                        throw new IOException(
                            "Missing runtime class: " + runtimePath
                        );
                    }
                    jar.putNextEntry(new JarEntry(runtimePath));
                    runtime.transferTo(jar);
                    jar.closeEntry();
                }
            }
        }
    }

    private static byte[] launcher() {
        return ClassFile.of().build(ClassDesc.of("Launcher"), writer -> {
            writer.withVersion(ClassFile.JAVA_25_VERSION, 0);
            writer.withFlags(ClassFile.ACC_PUBLIC);
            writer.withMethodBody(
                "main",
                MethodTypeDesc.ofDescriptor("([Ljava/lang/String;)V"),
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                method -> {
                    method.ldc("Test");
                    method.invokestatic(
                        ClassDesc.of("java.lang.Class"),
                        "forName",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/String;)Ljava/lang/Class;"
                        )
                    );
                    method.pop();
                    method.return_();
                }
            );
        });
    }
}
