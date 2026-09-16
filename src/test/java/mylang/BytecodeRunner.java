package mylang;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BytecodeRunner {
    private BytecodeRunner() {}

    public static String run(final Map<String, byte[]> classes)
        throws IOException,
        InterruptedException {
        final Path directory = Files.createTempDirectory("mylang-fixture-");
        try {
            for (final var entry : classes.entrySet()) {
                final Path file =
                    directory.resolve(
                        entry.getKey().replace('.', '/') + ".class"
                    );
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            final String java =
                Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    "java"
                ).toString();
            final String classpath =
                directory + File.pathSeparator
                    + System.getProperty("java.class.path");
            final Process process =
                new ProcessBuilder(
                    java,
                    "-Dfile.encoding=UTF-8",
                    "-Dstdout.encoding=UTF-8",
                    "-Dstderr.encoding=UTF-8",
                    "-cp",
                    classpath,
                    BytecodeRunner.class.getName()
                ).redirectErrorStream(true)
                    .start();
            try (final var reader = Executors.newVirtualThreadPerTaskExecutor()) {
                // Drain the pipe while waiting so large output cannot block the child.
                final var output = reader.submit(
                    () -> process.getInputStream().readAllBytes()
                );
                try {
                    process.getOutputStream().close();
                    if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                        throw new BytecodeException(
                            "Fixture execution timed out after 500 milliseconds"
                        );
                    }
                    final String captured;
                    try {
                        captured = new String(
                            output.get(), StandardCharsets.UTF_8
                        );
                    }
                    catch (final ExecutionException e) {
                        throw new IOException(
                            "Failed to capture fixture output", e.getCause()
                        );
                    }
                    if (process.exitValue() != 0) {
                        throw new BytecodeException(
                            "Fixture execution failed:\n%s",
                            captured
                        );
                    }
                    return captured.replace("\r\n", "\n");
                }
                finally {
                    if (process.isAlive()) {
                        process.destroyForcibly();
                        process.waitFor();
                    }
                    process.getInputStream().close();
                }
            }
        }
        finally {
            try (final var paths = Files.walk(directory)) {
                final var files =
                    paths.sorted(Comparator.reverseOrder()).toList();
                for (final Path path : files) {
                    Files.delete(path);
                }
            }
        }
    }

    public static void main(final String[] args) throws ClassNotFoundException {
        // Top-level statements are emitted into the module's static initializer.
        Class.forName("Test", true, BytecodeRunner.class.getClassLoader());
    }
}
