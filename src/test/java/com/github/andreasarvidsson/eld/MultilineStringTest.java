package com.github.andreasarvidsson.eld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.github.andreasarvidsson.eld.lexer.Lexer;
import com.github.andreasarvidsson.eld.lexer.LexerException;
import com.github.andreasarvidsson.eld.lexer.Token;
import com.github.andreasarvidsson.eld.lexer.TokenType;

public class MultilineStringTest {
    @Test
    void rejectsPhysicalNewlinesInSingleLineStrings() {
        for (final String source : List.of(
            "\"first\nsecond\"",
            "r\"first\nsecond\"",
            "f\"first\nsecond\"",
            "f\"{1\n+2}\"",
            "rf\"first\nsecond\"",
            "fr\"first\nsecond\""
        )) {
            final LexerException error =
                assertThrows(
                    LexerException.class,
                    () -> new Lexer(source).getTokens()
                );
            assertTrue(error.getMessage().contains("Unterminated"));
            assertTrue(error.getMessage().contains(" at 1:"));
        }
    }

    @Test
    void lineEndingsAndFollowingPositions() {
        final String source =
            "const value = \"\"\"\n    first\n  \n        second\n    \"\"\";\nprint(value);";
        final List<Token> lf = new Lexer(source).getTokens();
        final List<Token> crlf =
            new Lexer(source.replace("\n", "\r\n")).getTokens();

        assertEquals(
            "first\n\n    second",
            StringLiterals.decode(lf.get(3).text())
        );
        assertEquals(
            StringLiterals.decode(lf.get(3).text()),
            StringLiterals.decode(crlf.get(3).text())
        );
        assertEquals(new Range(1, 15, 5, 8), lf.get(3).range());
        assertEquals(lf.get(3).range(), crlf.get(3).range());
        assertEquals(new Range(6, 1, 6, 6), lf.get(5).range());
        assertEquals(lf.get(5).range(), crlf.get(5).range());
    }

    @Test
    void rawAndFormattedLineEndings() {
        final String source =
            "const raw = r\"\"\"\n    A\\t\n    B\n    \"\"\";\nconst formatted = f\"\"\"\n    Hi {raw}\n    \"\"\";";
        final List<Token> lf = new Lexer(source).getTokens();
        final List<Token> crlf =
            new Lexer(source.replace("\n", "\r\n")).getTokens();

        assertEquals("A\\t\nB", StringLiterals.decodeRaw(lf.get(3).text()));
        assertEquals(
            StringLiterals.decodeRaw(lf.get(3).text()),
            StringLiterals.decodeRaw(crlf.get(3).text())
        );
        assertEquals(
            lf.stream().map(Token::range).toList(),
            crlf.stream().map(Token::range).toList()
        );
        for (int i = 0; i < lf.size(); i++) {
            if (lf.get(i).type() == TokenType.STRING_LITERAL) {
                assertEquals(lf.get(i).text(), crlf.get(i).text());
            }
        }
    }
}
