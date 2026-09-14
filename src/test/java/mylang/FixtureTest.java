package mylang;

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

import mylang.lexer.Lexer;
import mylang.lexer.Token;

public class FixtureTest {

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

    private static void assertFixture(final Path path, final boolean updateFixture) throws IOException {
        final String fixture = Files.readString(path).replaceAll("\r\n", "\n");
        final int tokenHeaderIndex = fixture.indexOf("\n" + TOKENS_HEADER);
        final boolean assertFixture = !updateFixture;

        assertTrue(tokenHeaderIndex >= 0, () -> "Missing tokens header delimiter in " + path);

        final String source = Objects.requireNonNull(fixture.substring(0, tokenHeaderIndex));
        final StringBuilder actualBuilder = new StringBuilder();
        String expected = "";

        actualBuilder.append(source);

        try {
            appendHeader(actualBuilder, TOKENS_HEADER);
            expected = fixture.substring(tokenHeaderIndex + TOKENS_HEADER.length()).strip();
            final List<Token> tokens = new Lexer(source).getTokens();
            final String tokensString = joinList(tokens);
            appendOutput(actualBuilder, tokensString);

            if (assertFixture) {
                assertEquals(expected, tokensString, path.toString());
            }

        } catch (final Exception e) {
            final String message = String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage());
            appendOutput(actualBuilder, Objects.requireNonNull(message));

            if (assertFixture) {
                assertEquals(expected, message, path.toString());
            }
        }

        if (updateFixture) {
            Files.writeString(path, actualBuilder.toString());
        }
    }

    private static void appendHeader(final StringBuilder actualBuilder, final String headerDelimiter) {
        actualBuilder.append("\n").append(headerDelimiter).append("\n");
    }

    private static void appendOutput(final StringBuilder actualBuilder, final String output) {
        actualBuilder.append(output).append("\n");
    }

    private static String joinList(final List<?> tokens) {
        final List<String> tokenStrings = tokens.stream().map(o -> Objects.requireNonNull(o).toString()).toList();
        return Objects.requireNonNull(String.join("\n", tokenStrings));
    }

}
