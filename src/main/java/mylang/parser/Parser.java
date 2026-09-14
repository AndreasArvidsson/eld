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
        final List<BlockItem> items = new ArrayList<>();

        while (!isAtEnd()) {
            items.add(parseBlockItem());
        }

        if (items.isEmpty()) {
            return new Program(items, new Range(0, 0, 0, 0));
        }

        final Position start = items.get(0).range().start();
        final Position end = items.get(items.size() - 1).range().end();
        final Range range = new Range(start, end);

        return new Program(items, range);
    }

    private BlockStatement parseBlockStatement() {
        final Token open = expect(TokenType.LEFT_BRACE);
        final List<BlockItem> items = new ArrayList<>();

        while (!isAtEnd() && !check(TokenType.RIGHT_BRACE)) {
            items.add(parseBlockItem());
        }

        final Token close = expect(TokenType.RIGHT_BRACE);
        final Range range = new Range(open.range().start(), close.range().end());

        return new BlockStatement(items, range);
    }

    private BlockItem parseBlockItem() {
        Token token = current();
        advance();

        switch (token.type()) {
            case CONST:
                return parseVariableDeclaration(token, Mutability.CONST);
            case VAR:
                return parseVariableDeclaration(token, Mutability.VAR);
            case BREAK:
                return new BreakStatement(token.range());
            case CONTINUE:
                return new ContinueStatement(token.range());
            case WHILE:
                return parseWhileStatement(token);
            case DO:
                return parseDoWhileStatement(token);
            case IF:
                return parseIfStatement(token);
        }

        throw new ParserException(token.range(), "Expected declaration or statement");
    }

    private WhileStatement parseWhileStatement(final Token keyword) {
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = new Range(keyword.range().start(), body.range().end());
        return new WhileStatement(condition, body, range);
    }

    private DoWhileStatement parseDoWhileStatement(final Token keyword) {
        final BlockStatement body = parseBlockStatement();
        expect(TokenType.WHILE);
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final Range range = new Range(keyword.range().start(), condition.range().end());
        return new DoWhileStatement(body, condition, range);
    }

    private IfStatement parseIfStatement(final Token keyword) {
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement thenBranch = parseBlockStatement();
        final List<ElseIfBranch> elifBranches = new ArrayList<>();

        while (check(TokenType.ELIF)) {
            final Token elifKeyword = expect(TokenType.ELIF);
            expect(TokenType.LEFT_PAREN);
            final Expression elifCondition = parseExpression();
            expect(TokenType.RIGHT_PAREN);
            final BlockStatement elifBranch = parseBlockStatement();
            final Range elifRange = new Range(elifKeyword.range().start(), elifBranch.range().end());
            elifBranches.add(new ElseIfBranch(elifCondition, elifBranch, elifRange));
        }

        final BlockStatement elseBranch = match(TokenType.ELSE) ? parseBlockStatement() : null;

        final Range range = new Range(keyword.range().start(),
                (elseBranch != null ? elseBranch.range().end() : thenBranch.range().end()));
        return new IfStatement(condition, thenBranch, elifBranches, elseBranch, range);
    }

    private VariableDeclaration parseVariableDeclaration(final Token keyword, final Mutability mutability) {
        final Token name = expect(TokenType.IDENTIFIER);
        final @Nullable TypeNode type = match(TokenType.COLON) ? parseType() : null;
        expect(TokenType.EQUAL);
        final Expression initializer = parseExpression();
        final Range range = new Range(keyword.range().start(), initializer.range().end());
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

    public boolean check(final TokenType type) {
        return !isAtEnd() && current().type() == type;
    }

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
