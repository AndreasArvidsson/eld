package mylang.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

public class LexerTest {

    private final static String TOKENS_HEADER = "\n--- TOKENS ---\n";

    @TestFactory
    List<DynamicTest> fixtures() throws IOException {
        // Read source fixtures so updates are saved to the repository, not
        // target/test-classes.
        final Path directory = Path.of(System.getProperty("basedir", "."),
                "src", "test", "resources", "fixtures");
        final boolean updateFixtures = Boolean.getBoolean("updateFixtures");

        try (final var paths = Files.walk(directory)) {
            final List<DynamicTest> tests = new ArrayList<>();
            paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".fixture"))
                    .sorted()
                    .forEach(path -> {
                        Objects.requireNonNull(path);
                        tests.add(DynamicTest.dynamicTest(
                                Objects.requireNonNull(directory.relativize(path).toString()),
                                () -> assertFixture(path, updateFixtures)));
                    });
            assertFalse(tests.isEmpty(), "No lexer .fixture files found");
            return tests;
        }
    }

    private static void assertFixture(final Path path, final boolean updateFixtures) throws IOException {
        final String fixture = Files.readString(path).replaceAll("\r\n", "\n");
        final int delimiter = fixture.indexOf(TOKENS_HEADER);
        assertTrue(delimiter >= 0, () -> "Missing input/output delimiter in " + path);

        final String input = Objects.requireNonNull(fixture.substring(0, delimiter).strip());
        final String expected = fixture.substring(delimiter + TOKENS_HEADER.length()).strip();
        final Lexer lexer = new Lexer(input);
        String actual = "";

        try {
            actual = lexerToString(lexer);
        } catch (final Exception e) {
            actual = String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage());
        }

        if (updateFixtures) {
            final String updated = input + "\n" + TOKENS_HEADER + "\n" + actual + (actual.isEmpty() ? "" : "\n");
            Files.writeString(path, updated);
        } else {
            assertEquals(expected, actual, path.toString());
        }
    }

    private static String lexerToString(final Lexer lexer) {
        final List<String> tokens = new ArrayList<>();

        while (true) {
            final Token token = lexer.nextToken();
            if (token == null) {
                break;
            }
            tokens.add(token.toString());
        }

        return Objects.requireNonNull(String.join("\n", tokens));
    }

}
