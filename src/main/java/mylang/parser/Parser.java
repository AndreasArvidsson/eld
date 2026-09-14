package mylang.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

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
        if (tokens.isEmpty()) {
            return new Program(new ArrayList<>(), new Range(0, 0, 0, 0));
        }

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
        Token token = matchToken(TokenType.CONST);
        if (token != null) {
            return parseVariableDeclaration(token, Mutability.CONST);
        }

        token = matchToken(TokenType.VAR);
        if (token != null) {
            return parseVariableDeclaration(token, Mutability.VAR);
        }

        // if (isMatch(TokenType.FUNC)) {
        // return parseFunctionDeclaration();
        // }

        throw new ParserException(current().range(), "Expected declaration");
    }

    private VariableDeclaration parseVariableDeclaration(final Token mutableKeyword, final Mutability mutability) {
        final Token name = expect(TokenType.IDENTIFIER);
        final @Nullable TypeNode type = match(TokenType.COLON) ? parseType() : null;
        expect(TokenType.EQUAL);
        final Expression initializer = parseExpression();
        final Range range = new Range(mutableKeyword.range().start(), initializer.range().end());
        return new VariableDeclaration(mutability, name.text(), type, initializer, range);
    }

    private TypeNode parseType() {
        final Token name = expect(TokenType.IDENTIFIER);
        return new NamedTypeNode(name.text(), name.range());
    }

    private Expression parseExpression() {
        final Token token = current();
        advance();
        switch (token.type()) {
            case IDENTIFIER:
                return new IdentifierExpression(token.text(), token.range());
            case BOOLEAN_LITERAL:
                return new LiteralExpression(LiteralKind.BOOLEAN, token.text(), token.range());
            case INTEGER_LITERAL:
                return new LiteralExpression(LiteralKind.INTEGER, token.text(), token.range());
            case FLOAT_LITERAL:
                return new LiteralExpression(LiteralKind.FLOAT, token.text(), token.range());
            case STRING_LITERAL:
                return new LiteralExpression(LiteralKind.STRING, token.text(), token.range());
            case CHAR_LITERAL:
                return new LiteralExpression(LiteralKind.CHAR, token.text(), token.range());
            default:
                throw new ParserException(token.range(), "Expected expression");
        }
    }

    private Token current() {
        return Objects.requireNonNull(tokens.get(position));
    }

    private Token advance() {
        return Objects.requireNonNull(tokens.get(position++));
    }

    // private Token peek(final int offset) {
    // return Objects.requireNonNull(tokens.get(position + offset));
    // }

    private boolean isAtEnd() {
        return position >= tokens.size();
    }

    // public boolean isMatch(final TokenType type) {
    // return !isAtEnd() && current().type() == type;
    // }

    private boolean match(final TokenType type) {
        return matchToken(type) != null;
    }

    private @Nullable Token matchToken(final TokenType type) {
        if (isAtEnd() || current().type() != type) {
            return null;
        }

        return advance();
    }

    // private @Nullable Token matchToken(final @NonNull TokenType... types) {
    // for (final @NonNull TokenType type : types) {
    // final Token token = matchToken(type);
    // if (token != null) {
    // return token;
    // }
    // }
    // return null;
    // }

    private Token expect(final TokenType type) {
        if (isAtEnd()) {
            final Position position = tokens.get(tokens.size() - 1).range().end();
            throw new ParserException(
                    new Range(position, position),
                    "Expected %s but reached end of input", type);
        }

        final Token token = current();

        if (token.type() != type) {
            throw new ParserException(token.range(),
                    "Expected %s but found %s", type, token.type());
        }

        advance();

        return token;
    }
}
