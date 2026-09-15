package mylang.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
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

    private final List<@NonNull Token> tokens;
    private int position;

    public Parser(final List<@NonNull Token> tokens) {
        this.tokens = tokens;
        this.position = 0;
    }

    public Program parse() {
        final List<@NonNull BlockItem> items = new ArrayList<>();

        while (!isAtEnd()) {
            items.add(parseBlockItem());
        }

        if (items.isEmpty()) {
            return new Program(items, new Range(0, 0, 0, 0));
        }

        final Position start = Objects.requireNonNull(items.get(0)).range().start();
        final Position end = Objects.requireNonNull(items.get(items.size() - 1)).range().end();
        final Range range = new Range(start, end);

        return new Program(items, range);
    }

    private BlockStatement parseBlockStatement() {
        final Token open = expect(TokenType.LEFT_BRACE);
        final List<@NonNull BlockItem> items = new ArrayList<>();

        while (!isAtEnd() && !check(TokenType.RIGHT_BRACE)) {
            items.add(parseBlockItem());
        }

        final Token close = expect(TokenType.RIGHT_BRACE);
        final Range range = new Range(open.range().start(), close.range().end());

        return new BlockStatement(items, range);
    }

    private BlockItem parseBlockItem() {
        final Token token = advance();

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
            case FUNC:
                return parseFunctionDeclaration(token);
            case RETURN:
                return parseReturnStatement(token);
            case IDENTIFIER:
                if (check(TokenType.LEFT_PAREN)) {
                    final IdentifierExpression callee = new IdentifierExpression(token.text(), token.range());
                    final CallExpression call = parseCallExpression(callee);
                    return new ExpressionStatement(call, call.range());
                }
                break;
            case LEFT_PAREN:
                if (isLambdaAfterOpenParen()) {
                    final LambdaExpression lambda = parseLambdaExpression(token);
                    return new ExpressionStatement(lambda, lambda.range());
                }
                break;
            default:
                break;
        }

        throw new ParserException(token.range(), "Expected declaration or statement");
    }

    private ReturnStatement parseReturnStatement(final Token keyword) {
        final Expression value = !isAtEnd() && !check(TokenType.RIGHT_BRACE)
                && current().range().start().line() == keyword.range().start().line()
                        ? parseExpression()
                        : null;
        final Range range = value != null
                ? new Range(keyword.range().start(), value.range().end())
                : keyword.range();
        return new ReturnStatement(value, range);
    }

    private FunctionDeclaration parseFunctionDeclaration(final Token keyword) {
        final Token name = expect(TokenType.IDENTIFIER);
        final IdentifierDeclaration nameId = new IdentifierDeclaration(name.text(), name.range());
        expect(TokenType.LEFT_PAREN);
        final List<@NonNull Parameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                final Token paramName = expect(TokenType.IDENTIFIER);
                expect(TokenType.COLON);
                final TypeNode type = parseType();
                final Range paramRange = new Range(paramName.range().start(), type.range().end());
                final IdentifierDeclaration id = new IdentifierDeclaration(paramName.text(), paramName.range());
                parameters.add(new Parameter(id, type, paramRange));
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RIGHT_PAREN);
        final TypeNode returnType = check(TokenType.LEFT_BRACE) ? null : parseType();
        final BlockStatement body = parseBlockStatement();
        final Range range = new Range(keyword.range().start(), body.range().end());
        return new FunctionDeclaration(nameId, parameters, returnType, body, range);
    }

    private CallExpression parseCallExpression(final Expression callee) {
        expect(TokenType.LEFT_PAREN);
        final List<@NonNull Expression> arguments = new ArrayList<>();

        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                arguments.add(parseExpression());
            } while (match(TokenType.COMMA));
        }

        final Token close = expect(TokenType.RIGHT_PAREN);
        final Range range = new Range(callee.range().start(), close.range().end());
        return new CallExpression(callee, arguments, range);
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
        final Token value = expect(TokenType.IDENTIFIER);
        final Token index = match(TokenType.COMMA) ? expect(TokenType.IDENTIFIER) : null;
        final IdentifierDeclaration valueId = new IdentifierDeclaration(value.text(), value.range());
        final IdentifierDeclaration indexId = index != null ? new IdentifierDeclaration(index.text(), index.range())
                : null;
        expect(TokenType.COLON);
        final Expression iterable = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = new Range(keyword.range().start(), body.range().end());
        return new ForEachStatement(valueId, indexId, iterable, body, range);
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
        final Token close = expect(TokenType.RIGHT_PAREN);
        final Range range = new Range(keyword.range().start(), close.range().end());
        return new DoWhileStatement(body, condition, range);
    }

    private IfStatement parseIfStatement(final Token keyword) {
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement thenBranch = parseBlockStatement();
        final List<@NonNull ElseIfBranch> elifBranches = new ArrayList<>();

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

        final Position end = elseBranch != null
                ? elseBranch.range().end()
                : elifBranches.isEmpty()
                        ? thenBranch.range().end()
                        : Objects.requireNonNull(elifBranches.getLast()).range().end();
        final Range range = new Range(keyword.range().start(), end);
        return new IfStatement(condition, thenBranch, elifBranches, elseBranch, range);
    }

    private VariableDeclaration parseVariableDeclaration(final Token keyword, final Mutability mutability) {
        final Token name = expect(TokenType.IDENTIFIER);
        final @Nullable TypeNode type = match(TokenType.COLON) ? parseType() : null;
        final IdentifierDeclaration identifier = new IdentifierDeclaration(name.text(), name.range());

        // var with no initializer
        if (mutability == Mutability.VAR && !check(TokenType.EQUAL)) {
            final Position end = type != null ? type.range().end() : name.range().end();
            final Range range = new Range(keyword.range().start(), end);
            return new VariableDeclaration(mutability, identifier, type, null, range);
        }

        expect(TokenType.EQUAL);
        final Expression initializer = parseExpression();
        final Range range = new Range(keyword.range().start(), initializer.range().end());
        return new VariableDeclaration(mutability, identifier, type, initializer, range);
    }

    private TypeNode parseType() {
        final Token name = expect(TokenType.IDENTIFIER);
        return new NamedTypeNode(name.text(), name.range());
    }

    private Expression parseExpression() {
        return parseBinaryExpression(1);
    }

    private Expression parseBinaryExpression(final int minimumPrecedence) {
        Expression left = parseUnaryExpression();
        while (!isAtEnd()) {
            final BinaryOperator operator = BINARY_OPERATORS.get(current().type());
            if (operator == null || precedence(operator) < minimumPrecedence) {
                break;
            }
            advance();
            final Expression right = parseBinaryExpression(precedence(operator) + 1);
            left = new BinaryExpression(left, operator, right,
                    new Range(left.range().start(), right.range().end()));
        }
        return left;
    }

    private static int precedence(final BinaryOperator operator) {
        return switch (operator) {
            case OR -> 1;
            case AND -> 2;
            case EQUAL, NOT_EQUAL -> 3;
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> 4;
            case ADD, SUBTRACT -> 5;
            case MULTIPLY, DIVIDE, MODULO -> 6;
        };
    }

    private Expression parseUnaryExpression() {
        if (isAtEnd()) {
            final Position end = Objects.requireNonNull(tokens.get(tokens.size() - 1)).range().end();
            throw new ParserException(new Range(end, end), "Expected expression, but reached end of input");
        }
        final Token token = advance();
        final UnaryOperator operator = switch (token.type()) {
            case PLUS -> UnaryOperator.PLUS;
            case MINUS -> UnaryOperator.MINUS;
            case BANG -> UnaryOperator.NOT;
            default -> null;
        };
        if (operator != null) {
            final Expression operand = parseUnaryExpression();
            return new UnaryExpression(operator, operand,
                    new Range(token.range().start(), operand.range().end()));
        }
        Expression left = parsePrimitiveExpression(token);
        while (!isAtEnd()) {
            final Token next = current();
            final PostfixOperator postfix = POSTFIX_OPERATORS.get(next.type());
            if (postfix != null) {
                advance();
                left = parsePostfixExpression(left, postfix, next);
            } else if (check(TokenType.LEFT_PAREN)) {
                left = parseCallExpression(left);
            } else {
                break;
            }
        }
        return left;
    }

    private Expression parsePrimitiveExpression(final Token token) {
        return switch (token.type()) {
            case IDENTIFIER ->
                new IdentifierExpression(token.text(), token.range());
            case BOOLEAN_LITERAL ->
                new LiteralExpression(LiteralKind.BOOL, token.text(), token.range());
            case INTEGER_LITERAL ->
                new LiteralExpression(LiteralKind.INT, token.text(), token.range());
            case FLOAT_LITERAL ->
                new LiteralExpression(LiteralKind.FLOAT, token.text(), token.range());
            case STRING_LITERAL ->
                new LiteralExpression(LiteralKind.STRING, token.text(), token.range());
            case CHAR_LITERAL ->
                new LiteralExpression(LiteralKind.CHAR, token.text(), token.range());
            case NULL ->
                new LiteralExpression(LiteralKind.NULL, token.text(), token.range());
            case LEFT_PAREN -> {
                if (isLambdaAfterOpenParen()) {
                    yield parseLambdaExpression(token);
                } else {
                    yield parseGroupingExpression(token);
                }
            }
            case LEFT_BRACKET ->
                parseArrayExpression(token);
            default ->
                throw new ParserException(token.range(), "Expected expression, but found %s", token.type());
        };
    }

    private GroupingExpression parseGroupingExpression(final Token open) {
        final Expression expression = parseExpression();
        final Token close = expect(TokenType.RIGHT_PAREN);
        final Range range = new Range(open.range().start(), close.range().end());
        return new GroupingExpression(expression, range);
    }

    private ArrayExpression parseArrayExpression(final Token open) {
        final List<@NonNull Expression> elements = new ArrayList<>();
        if (!check(TokenType.RIGHT_BRACKET)) {
            do {
                elements.add(parseExpression());
            } while (match(TokenType.COMMA));
        }
        final Token close = expect(TokenType.RIGHT_BRACKET);
        final Range range = new Range(open.range().start(), close.range().end());
        return new ArrayExpression(elements, range);
    }

    // The opening parenthesis has already been consumed. Lookahead leaves position
    // unchanged.
    private boolean isLambdaAfterOpenParen() {
        int depth = 1;
        for (int offset = 0; position + offset < tokens.size(); offset++) {
            final TokenType type = Objects.requireNonNull(tokens.get(position + offset)).type();
            if (type == TokenType.LEFT_PAREN) {
                depth++;
            } else if (type == TokenType.RIGHT_PAREN && --depth == 0) {
                return check(offset + 1, TokenType.FAT_ARROW)
                        || (check(offset + 1, TokenType.IDENTIFIER) && check(offset + 2, TokenType.FAT_ARROW));
            }
        }
        return false;
    }

    private LambdaExpression parseLambdaExpression(final Token open) {
        final List<@NonNull Parameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                final Token name = expect(TokenType.IDENTIFIER);
                final TypeNode type = match(TokenType.COLON) ? parseType() : null;
                final Position end = type == null ? name.range().end() : type.range().end();
                final Range range = new Range(name.range().start(), end);
                final IdentifierDeclaration id = new IdentifierDeclaration(name.text(), name.range());
                parameters.add(new Parameter(id, type, range));
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RIGHT_PAREN);
        final TypeNode returnType = check(TokenType.FAT_ARROW) ? null : parseType();
        expect(TokenType.FAT_ARROW);
        final AstNode body = check(TokenType.LEFT_BRACE) ? parseBlockStatement() : parseExpression();
        final Range range = new Range(open.range().start(), body.range().end());
        return new LambdaExpression(parameters, returnType, body, range);
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

    private boolean isAtEnd() {
        return position >= tokens.size();
    }

    public boolean check(final TokenType type) {
        return !isAtEnd() && current().type() == type;
    }

    private boolean check(final int offset, final TokenType type) {
        return position + offset < tokens.size()
                && Objects.requireNonNull(tokens.get(position + offset)).type() == type;
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

    private Token expect(final TokenType type) {
        if (isAtEnd()) {
            final Position position = Objects.requireNonNull(tokens.get(tokens.size() - 1)).range().end();
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
