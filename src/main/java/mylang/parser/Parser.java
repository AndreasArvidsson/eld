package mylang.parser;

import java.util.ArrayList;
import java.util.List;

import mylang.Position;
import mylang.Range;
import mylang.lexer.Token;
import mylang.lexer.TokenType;

public final class Parser {

    private final List<Token> tokens;
    private int position;

    public Parser(final List<Token> tokens) {
        this.tokens = tokens;
        this.position = 0;
    }

    public Program parse() {
        final List<Declaration> declarations = new ArrayList<>();

        while (!isAtEnd()) {
            declarations.add(parseDeclaration());
        }

        if (declarations.isEmpty()) {
            return new Program(declarations, new Range(0, 0, 0, 0));
        }

        final Position start = declarations.get(0).range().start();
        final Position end = declarations.get(declarations.size() - 1).range().end();
        final Range range = new Range(start, end);

        return new Program(declarations, range);
    }

    private Declaration parseDeclaration() {
        // if (match(TokenType.CONST)) {
        // return parseVariableDeclaration(Mutability.CONST);
        // }

        // if (match(TokenType.VAR)) {
        // return parseVariableDeclaration(Mutability.VAR);
        // }

        // if (match(TokenType.FUNC)) {
        // return parseFunctionDeclaration();
        // }

        throw new ParserException(
                current().range(),
                "Expected declaration");
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

    private boolean isAtEnd() {
        return position >= tokens.size();
    }

    private boolean match(TokenType type) {
        if (current().type() != type) {
            return false;
        }

        advance();
        return true;
    }
}
