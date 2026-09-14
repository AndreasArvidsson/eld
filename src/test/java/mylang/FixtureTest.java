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
import mylang.parser.AstNode;
import mylang.parser.Parser;

public class FixtureTest {

    private final static String TOKENS_HEADER = "\n\n--- TOKENS ---\n\n";
    private final static String AST_HEADER = "\n\n--- AST ---\n\n";
    private final static String SEMANTIC_HEADER = "\n\n--- SEMANTIC ---\n\n";
    // private final static String BYTECODE_HEADER = "\n\n--- BYTECODE ---\n\n";
    // private final static String OUTPUT_HEADER = "\n\n--- OUTPUT ---\n\n";
    private final static String FIXTURE_EXTENSION = ".fixture";

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
                    .filter(path -> path.getFileName().toString().endsWith(FIXTURE_EXTENSION))
                    .sorted()
                    .forEach(path -> {
                        Objects.requireNonNull(path);
                        final String filename = path.getFileName().toString();
                        final String name = Objects
                                .requireNonNull(filename.substring(0, filename.length() - FIXTURE_EXTENSION.length()));
                        tests.add(DynamicTest.dynamicTest(
                                name,
                                () -> assertFixture(path, name, updateFixtures)));
                    });

            assertFalse(tests.isEmpty(), "No fixture files found");
            return tests;
        }
    }

    private static void assertFixture(
            final Path path,
            final String name,
            final boolean updateFixture)
            throws IOException {
        final String fixture = Files.readString(path).replaceAll("\r\n", "\n");
        final int tokenHeaderIndex = fixture.indexOf(TOKENS_HEADER);
        final int astHeaderIndex = fixture.indexOf(AST_HEADER);
        final int semanticHeaderIndex = fixture.indexOf(SEMANTIC_HEADER);
        final boolean assertFixture = !updateFixture;
        final String source = getContent(fixture, "", 0, tokenHeaderIndex);
        final StringBuilder actualBuilder = new StringBuilder();
        String expected = "";

        actualBuilder.append(source);

        try {
            actualBuilder.append(TOKENS_HEADER);
            if (assertFixture) {
                assertTrue(tokenHeaderIndex >= 0, () -> "Missing tokens header delimiter in " + name);
            }
            expected = getContent(fixture, TOKENS_HEADER, tokenHeaderIndex, astHeaderIndex);
            final List<Token> tokens = new Lexer(source).getTokens();
            final String tokensActual = joinList(tokens);
            actualBuilder.append(tokensActual);

            if (assertFixture) {
                assertEquals(expected, tokensActual, name);
            }

            actualBuilder.append(AST_HEADER);
            if (assertFixture) {
                assertTrue(astHeaderIndex >= 0, () -> "Missing AST header delimiter in " + name);
            }
            expected = getContent(fixture, AST_HEADER, astHeaderIndex,
                    semanticHeaderIndex);
            final AstNode ast = new Parser(tokens).parse();
            final String astActual = ast.toString();
            actualBuilder.append(astActual);

            if (assertFixture) {
                assertEquals(expected, astActual, name);
            }
        } catch (final Exception e) {
            final String message = String.format("%s: %s", e.getClass().getSimpleName(), e.getMessage());
            actualBuilder.append(message);

            if (assertFixture) {
                assertEquals(expected, message, name);
            }
        }

        actualBuilder.append("\n");

        if (updateFixture) {
            Files.writeString(path, actualBuilder.toString());
        }
    }

    private static String getContent(final String fixture, final String header, final int headerIndex,
            final int nextHeaderIndex) {
        final int endIndex = nextHeaderIndex == -1 ? fixture.length() - 1 : nextHeaderIndex;
        final String result = fixture.substring(headerIndex + header.length(), endIndex);
        return Objects.requireNonNull(result);
    }

    private static String joinList(final List<?> tokens) {
        final List<String> tokenStrings = tokens.stream().map(o -> Objects.requireNonNull(o).toString()).toList();
        return Objects.requireNonNull(String.join("\n", tokenStrings));
    }

}
