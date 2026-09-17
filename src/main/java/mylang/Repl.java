package mylang;

import java.io.IOException;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.TerminalBuilder;

final class Repl {
    private Repl() {}

    static void run() throws IOException {
        try (final var terminal =
            TerminalBuilder.builder().system(true).build()) {
            final DefaultParser parser = new DefaultParser();
            parser.setEofOnUnclosedBracket(
                DefaultParser.Bracket.ROUND,
                DefaultParser.Bracket.CURLY,
                DefaultParser.Bracket.SQUARE
            );
            parser.setEofOnUnclosedQuote(true);
            parser.setLineCommentDelims(new String[] {"//"});
            final var reader =
                LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(parser)
                    .completer(
                        new StringsCompleter(
                            ":help",
                            ":quit",
                            ":reset",
                            "const",
                            "var",
                            "func",
                            "class",
                            "print",
                            "if",
                            "while",
                            "for",
                            "return",
                            "yield"
                        )
                    )
                    .build();
            ReplSession session = new ReplSession();
            terminal.writer()
                .println(
                    String.format(
                        "%s REPL. :help for commands; Ctrl-D to exit.",
                        Constants.LANGUAGE_NAME
                    )
                );
            terminal.flush();
            while (true) {
                try {
                    final String line = reader.readLine("iz> ");
                    switch (line.trim()) {
                        case ":quit", ":exit" -> {
                            return;
                        }
                        case ":help" -> terminal.writer()
                            .println(
                                ":help  Show help\n:reset Clear session\n:quit  Exit\nEnd simple statements with ;. Expressions print their values. Ctrl-C cancels input."
                            );
                        case ":reset" -> session = new ReplSession();
                        default -> session.evaluate(line);
                    }
                }
                catch (final UserInterruptException e) {
                    // Cancel the current input and keep the session.
                }
                catch (final EndOfFileException e) {
                    return;
                }
                catch (
                    final
                        ReflectiveOperationException
                        | RuntimeException
                        | LinkageError e
                ) {
                    terminal.writer().println("Error: " + Main.diagnostic(e));
                }
                terminal.flush();
            }
        }
    }
}
