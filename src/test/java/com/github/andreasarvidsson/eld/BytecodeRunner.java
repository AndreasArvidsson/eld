package com.github.andreasarvidsson.eld;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

public final class BytecodeRunner {
    private static final Object LOCK = new Object();
    private static final long TIMEOUT_MILLIS = 500;
    private static @Nullable Worker worker;

    private BytecodeRunner() {}

    public static String run(final Map<String, byte[]> classes)
        throws IOException,
        InterruptedException {
        synchronized (LOCK) {
            Worker current = worker;
            if (current == null || !current.isAlive()) {
                current = Worker.start();
                worker = current;
            }
            try {
                return current.run(classes);
            }
            catch (final IOException | InterruptedException e) {
                discardWorker();
                throw e;
            }
        }
    }

    private static void discardWorker() throws InterruptedException {
        final Worker current = worker;
        if (current != null) {
            current.close();
            worker = null;
        }
    }

    private static final class Worker {
        private final Process process;
        private final DataInputStream input;
        private final DataOutputStream output;

        private Worker(final Process process) {
            this.process = process;
            input = new DataInputStream(process.getInputStream());
            output = new DataOutputStream(process.getOutputStream());
        }

        static Worker start() throws IOException {
            final String java =
                Path.of(System.getProperty("java.home"), "bin", "java")
                    .toString();
            final Process process =
                new ProcessBuilder(
                    java,
                    "-Dfile.encoding=UTF-8",
                    "-Dstdout.encoding=UTF-8",
                    "-Dstderr.encoding=UTF-8",
                    "-cp",
                    System.getProperty("java.class.path"),
                    BytecodeRunner.class.getName(),
                    "--worker"
                ).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            return new Worker(process);
        }

        boolean isAlive() {
            return process.isAlive();
        }

        String run(final Map<String, byte[]> classes)
            throws IOException,
            InterruptedException {
            output.writeInt(classes.size());
            for (final var entry : classes.entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeInt(entry.getValue().length);
                output.write(entry.getValue());
            }
            output.flush();

            try (final var reader =
                Executors.newVirtualThreadPerTaskExecutor()) {
                final var response = reader.submit(this::readResponse);
                try {
                    return response.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                }
                catch (final InterruptedException e) {
                    close();
                    throw e;
                }
                catch (final TimeoutException e) {
                    close();
                    throw new BytecodeException(
                        "Fixture execution timed out after %d milliseconds",
                        TIMEOUT_MILLIS
                    );
                }
                catch (final ExecutionException e) {
                    if (e.getCause() instanceof BytecodeException bytecode) {
                        throw bytecode;
                    }
                    if (e.getCause() instanceof IOException io) {
                        throw io;
                    }
                    throw new IOException(
                        "Failed to capture fixture output",
                        e.getCause()
                    );
                }
            }
        }

        private String readResponse() throws IOException {
            final boolean success = input.readBoolean();
            final int length = input.readInt();
            final String captured =
                new String(input.readNBytes(length), StandardCharsets.UTF_8);
            if (!success) {
                throw new BytecodeException(
                    "Fixture execution failed:\n%s",
                    captured
                );
            }
            return captured.replace("\r\n", "\n");
        }

        void close() throws InterruptedException {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    public static void main(final String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--worker")) {
            workerLoop();
            return;
        }
        throw new IllegalArgumentException("Expected --worker");
    }

    private static void workerLoop() throws IOException {
        final DataInputStream input = new DataInputStream(System.in);
        try (final DataOutputStream output =
            new DataOutputStream(new FileOutputStream(FileDescriptor.out))) {
            while (true) {
                final int count;
                try {
                    count = input.readInt();
                }
                catch (final EOFException e) {
                    return;
                }
                final Map<String, byte[]> classes = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    final String name = input.readUTF();
                    classes.put(name, input.readNBytes(input.readInt()));
                }
                final ByteArrayOutputStream captured =
                    new ByteArrayOutputStream();
                boolean success = true;
                try (final PrintStream stream =
                    new PrintStream(captured, true, StandardCharsets.UTF_8)) {
                    final PrintStream previousOut = System.out;
                    final PrintStream previousErr = System.err;
                    System.setOut(stream);
                    System.setErr(stream);
                    try {
                        final ModuleLoader loader = new ModuleLoader();
                        loader.add(classes);
                        Class.forName("Test", true, loader);
                        Class.forName(
                            "com.github.andreasarvidsson.eld.runtime.EldScheduler",
                            true,
                            loader
                        ).getMethod("drain").invoke(null);
                    }
                    catch (final Throwable error) {
                        success = false;
                        error.printStackTrace(stream);
                    }
                    finally {
                        System.setOut(previousOut);
                        System.setErr(previousErr);
                    }
                }
                final byte[] bytes = captured.toByteArray();
                output.writeBoolean(success);
                output.writeInt(bytes.length);
                output.write(bytes);
                output.flush();
            }
        }
    }
}
