package mylang.parser;

import java.util.List;
import mylang.lexer.Token;

public final class Parser {

    private final List<Token> tokens;
    private int position;

    public Parser(final List<Token> tokens) {
        this.tokens = tokens;
        this.position = 0;
    }

    private Token current() {
        return tokens.get(position);
    }

    private Token advance() {
        return tokens.get(position++);
    }

    private Token peek(final int offset) {
        return tokens.get(position + offset);
    }
}
