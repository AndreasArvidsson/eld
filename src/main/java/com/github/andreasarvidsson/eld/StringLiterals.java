package com.github.andreasarvidsson.eld;

import java.util.ArrayList;
import java.util.List;

public final class StringLiterals {
    private StringLiterals() {}

    public static String decode(final String text) {
        final String content =
            text.startsWith("\"\"\"")
                ? multilineContent(text)
                : text.substring(1, text.length() - 1);
        return decodeContent(content);
    }

    public static String decodeContent(final String content) {
        final StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (
                c == '\\' && i + 1 < content.length()
                    && "btnfr0'\"\\".indexOf(content.charAt(i + 1)) >= 0
            ) {
                c = switch (content.charAt(++i)) {
                    case 'b' -> '\b';
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'f' -> '\f';
                    case 'r' -> '\r';
                    case '0' -> '\0';
                    default -> content.charAt(i);
                };
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    public static String decodeRaw(final String text) {
        return text.startsWith("\"\"\"")
            ? multilineContent(text)
            : text.substring(1, text.length() - 1);
    }

    private static String multilineContent(final String text) {
        final String normalized = text.replace("\r\n", "\n");
        final String body = normalized.substring(4, normalized.length() - 3);
        final String[] lines = body.split("\n", -1);
        final String indentation = lines[lines.length - 1];
        final List<String> content = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            content.add(
                lines[i].isBlank()
                    ? ""
                    : lines[i].substring(indentation.length())
            );
        }
        return String.join("\n", content);
    }
}
