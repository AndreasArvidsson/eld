package mylang.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import mylang.Position;
import mylang.Range;
import mylang.lexer.Token;
import mylang.lexer.TokenType;

public final class Parser {

    private static final Map<TokenType, BinaryOperator> BINARY_OPERATORS = Objects.requireNonNull(Map.ofEntries(
            Map.entry(TokenType.PLUS, BinaryOperator.ADD),
            Map.entry(TokenType.MINUS, BinaryOperator.SUBTRACT),
            Map.entry(TokenType.STAR, BinaryOperator.MULTIPLY),
            Map.entry(TokenType.SLASH, BinaryOperator.DIVIDE),
            Map.entry(TokenType.PERCENT, BinaryOperator.MODULO),
            Map.entry(TokenType.EQUAL_EQUAL, BinaryOperator.EQUAL),
            Map.entry(TokenType.BANG_EQUAL, BinaryOperator.NOT_EQUAL),
            Map.entry(TokenType.LESS, BinaryOperator.LESS),
            Map.entry(TokenType.LESS_EQUAL, BinaryOperator.LESS_EQUAL),
            Map.entry(TokenType.GREATER, BinaryOperator.GREATER),
            Map.entry(TokenType.GREATER_EQUAL, BinaryOperator.GREATER_EQUAL),
            Map.entry(TokenType.AND, BinaryOperator.AND),
            Map.entry(TokenType.OR, BinaryOperator.OR)));

    private static final Map<TokenType, PostfixOperator> POSTFIX_OPERATORS = Objects.requireNonNull(Map.ofEntries(
            Map.entry(TokenType.PLUS_PLUS, PostfixOperator.INCREMENT),
            Map.entry(TokenType.MINUS_MINUS, PostfixOperator.DECREMENT)));

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
            case FOR:
                return parseForStatement(token);
        }

        throw new ParserException(token.range(), "Expected declaration or statement");
    }

    private Statement parseForStatement(final Token keyword) {
        expect(TokenType.LEFT_PAREN);

        if (isForEachHeader()) {
            return parseForEachStatement(keyword);
        }
        return parseCountedForStatement(keyword);
    }

    private boolean isForEachHeader() {
        return check(TokenType.IDENTIFIER)
                && (check(1, TokenType.COLON)
                        || (check(1, TokenType.COMMA)
                                && check(2, TokenType.IDENTIFIER)
                                && check(3, TokenType.COLON)));
    }

    private ForStatement parseCountedForStatement(final Token keyword) {
        final @Nullable Statement initializer;
        if (check(TokenType.SEMICOLON)) {
            initializer = null;
        } else if (check(TokenType.VAR) || check(TokenType.CONST)) {
            final Token declarationKeyword = advance();
            final Mutability mutability = declarationKeyword.type() == TokenType.VAR
                    ? Mutability.VAR
                    : Mutability.CONST;
            final VariableDeclaration declaration = parseVariableDeclaration(declarationKeyword, mutability);
            initializer = new DeclarationStatement(declaration, declaration.range());
        } else {
            final Expression expression = parseExpression();
            initializer = new ExpressionStatement(expression, expression.range());
        }
        expect(TokenType.SEMICOLON);

        final @Nullable Expression condition = check(TokenType.SEMICOLON) ? null : parseExpression();
        expect(TokenType.SEMICOLON);
        final @Nullable Expression update = check(TokenType.RIGHT_PAREN) ? null : parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = new Range(keyword.range().start(), body.range().end());
        return new ForStatement(initializer, condition, update, body, range);
    }

    private ForEachStatement parseForEachStatement(final Token keyword) {
        final String valueName = expect(TokenType.IDENTIFIER).text();
        final String indexName = match(TokenType.COMMA) ? expect(TokenType.IDENTIFIER).text() : null;
        expect(TokenType.COLON);
        final Expression iterable = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = new Range(keyword.range().start(), body.range().end());
        return new ForEachStatement(valueName, indexName, iterable, body, range);
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
        if (isAtEnd()) {
            final Position end = tokens.get(tokens.size() - 1).range().end();
            throw new ParserException(new Range(end, end), "Expected expression, but reached end of input");
        }
        final Token token = advance();

        final Expression left = parsePrimitiveExpression(token);

        if (isAtEnd()) {
            return left;
        }

        final Token next = peek(0);
        final BinaryOperator binaryOperator = BINARY_OPERATORS.get(next.type());

        if (binaryOperator != null) {
            advance();
            return parseBinaryExpression(left, binaryOperator);
        }

        final PostfixOperator unaryOperator = POSTFIX_OPERATORS.get(next.type());

        if (unaryOperator != null) {
            advance();
            return parsePostfixExpression(left, unaryOperator, next);
        }

        return left;
    }

    private Expression parsePrimitiveExpression(final Token token) {
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

    private Expression parseBinaryExpression(final Expression left, final BinaryOperator operator) {
        final Expression right = parseExpression();
        final Range range = new Range(left.range().start(), right.range().end());
        return new BinaryExpression(left, operator, right, range);
    }

    private PostfixExpression parsePostfixExpression(final Expression operand, final PostfixOperator operator,
            final Token operatorToken) {
        final Range range = new Range(operand.range().start(), operatorToken.range().end());
        return new PostfixExpression(operand, operator, range);
    }

    private Token current() {
        return Objects.requireNonNull(tokens.get(position));
    }

    private Token advance() {
        return Objects.requireNonNull(tokens.get(position++));
    }

    private Token peek(final int offset) {
        return Objects.requireNonNull(tokens.get(position + offset));
    }

    private boolean isAtEnd() {
        return position >= tokens.size();
    }

    public boolean check(final TokenType type) {
        return !isAtEnd() && current().type() == type;
    }

    private boolean check(final int offset, final TokenType type) {
        return position + offset < tokens.size() && tokens.get(position + offset).type() == type;
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
