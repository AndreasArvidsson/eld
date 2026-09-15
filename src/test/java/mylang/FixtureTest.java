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

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import mylang.lexer.Lexer;
import mylang.lexer.LexerException;
import mylang.lexer.Token;
import mylang.parser.Parser;
import mylang.parser.ParserException;
import mylang.parser.Program;
import mylang.semantic.SemanticAnalyzer;
import mylang.semantic.SemanticException;
import mylang.semantic.SemanticModel;

public class FixtureTest {

    private final static String TOKENS_HEADER = "\n\n--- TOKENS ---\n\n";
    private final static String AST_HEADER = "\n\n--- AST ---\n\n";
    private final static String SEMANTIC_HEADER = "\n\n--- SEMANTIC ---\n\n";
    private final static String BYTECODE_HEADER = "\n\n--- BYTECODE ---\n\n";
    // private final static String OUTPUT_HEADER = "\n\n--- OUTPUT ---\n\n";

    @TestFactory
    List<DynamicTest> fixtures() throws IOException {
        final boolean updateFixtures = Boolean.getBoolean("updateFixtures");
        final List<DynamicTest> tests = new ArrayList<>();
        final List<@NonNull Fixture> fixtures = Fixtures.getFixtures();

        assertFalse(fixtures.isEmpty(), "No fixture files found");

        for (final Fixture fixture : fixtures) {
            final Path path = fixture.path();
            final String name = fixture.name();
            tests.add(DynamicTest.dynamicTest(
                    name,
                    () -> assertFixture(path, name, updateFixtures)));
        }

        return tests;
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
        final int bytecodeHeaderIndex = fixture.indexOf(BYTECODE_HEADER);
        final boolean assertFixture = !updateFixture;
        final String source = tokenHeaderIndex < 0 ? fixture : getContent(fixture, "", 0, tokenHeaderIndex);
        final StringBuilder actualBuilder = new StringBuilder();
        String expected = "";

        actualBuilder.append(source);

        try {
            actualBuilder.append(TOKENS_HEADER);
            if (assertFixture) {
                assertTrue(tokenHeaderIndex >= 0, () -> "Missing tokens header delimiter in " + name);
            }
            expected = getContent(fixture, TOKENS_HEADER, tokenHeaderIndex, astHeaderIndex);
            final List<@NonNull Token> tokens = new Lexer(source).getTokens();
            final String tokensActual = joinList(tokens);
            actualBuilder.append(tokensActual);

            if (assertFixture) {
                assertEquals(expected, tokensActual, name);
            }

            actualBuilder.append(AST_HEADER);
            if (assertFixture) {
                assertTrue(astHeaderIndex >= 0, () -> "Missing AST header delimiter in " + name);
            }
            expected = getContent(fixture, AST_HEADER, astHeaderIndex, semanticHeaderIndex);
            final Program ast = new Parser(tokens).parse();
            final String astActual = ast.toAstString();
            actualBuilder.append(astActual);

            if (assertFixture) {
                assertEquals(expected, astActual, name);
            }

            actualBuilder.append(SEMANTIC_HEADER);
            if (assertFixture) {
                assertTrue(semanticHeaderIndex >= 0, () -> "Missing semantic header delimiter in " + name);
            }
            expected = getContent(fixture, SEMANTIC_HEADER, semanticHeaderIndex, bytecodeHeaderIndex);
            final SemanticModel semanticModel = new SemanticAnalyzer().analyze(ast);
            final String semanticActual = semanticModel.toString();
            actualBuilder.append(semanticActual);

            if (assertFixture) {
                assertEquals(expected, semanticActual, name);
            }
        } catch (final LexerException | ParserException | SemanticException e) {
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

    private static String getContent(
            final String fixture,
            final String header,
            final int headerIndex,
            final int nextHeaderIndex) {
        if (headerIndex == -1) {
            return "";
        }
        final int startIndex = headerIndex + header.length();
        int endIndex = nextHeaderIndex == -1 ? fixture.length() : nextHeaderIndex;
        if (nextHeaderIndex == -1 && endIndex > startIndex && fixture.endsWith("\n")) {
            endIndex--;
        }
        final String result = fixture.substring(startIndex, endIndex);
        return Objects.requireNonNull(result);
    }

    private static String joinList(final List<? extends Object> tokens) {
        final List<String> tokenStrings = tokens.stream().map(o -> o.toString()).toList();
        return Objects.requireNonNull(String.join("\n", tokenStrings));
    }

}
