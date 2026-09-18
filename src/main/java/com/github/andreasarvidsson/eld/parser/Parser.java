package com.github.andreasarvidsson.eld.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.github.andreasarvidsson.eld.Position;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.lexer.Token;
import com.github.andreasarvidsson.eld.lexer.TokenType;

public final class Parser {

    private static final Map<TokenType, BinaryOperator> BINARY_OPERATORS =
        Objects.requireNonNull(
            Map.ofEntries(
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
                Map.entry(
                    TokenType.GREATER_EQUAL,
                    BinaryOperator.GREATER_EQUAL
                ),
                Map.entry(TokenType.AND, BinaryOperator.AND),
                Map.entry(TokenType.OR, BinaryOperator.OR)
            )
        );

    private static final Map<TokenType, PostfixOperator> POSTFIX_OPERATORS =
        Objects.requireNonNull(
            Map.ofEntries(
                Map.entry(TokenType.PLUS_PLUS, PostfixOperator.INCREMENT),
                Map.entry(TokenType.MINUS_MINUS, PostfixOperator.DECREMENT)
            )
        );

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

        final Range range =
            items.getFirst().range().union(items.getLast().range());

        return new Program(items, range);
    }

    private BlockStatement parseBlockStatement() {
        final Token open = expect(TokenType.LEFT_BRACE);
        final List<@NonNull BlockItem> items = new ArrayList<>();

        while (!isAtEnd() && !check(TokenType.RIGHT_BRACE)) {
            items.add(parseBlockItem());
        }

        final Token close = expect(TokenType.RIGHT_BRACE);
        final Range range = open.range().union(close.range());

        return new BlockStatement(items, range);
    }

    private BlockItem parseBlockItem() {
        final Token token = advance();

        switch (token.type()) {
            case CONST:
                return parseVariableDeclaration(token, Mutability.CONST);
            case VAR:
                return parseVariableDeclaration(token, Mutability.VAR);
            case CLASS:
                return parseClassDeclaration(token);
            case CONSTRUCTOR:
                return parseConstructorDeclaration(token);
            case BREAK:
                return parseBreakStatement(token);
            case CONTINUE:
                return parseContinueStatement(token);
            case WHILE:
                return parseWhileStatement(token);
            case DO:
                return parseDoWhileStatement(token);
            case FOR:
                return parseForStatement(token);
            case FUNC:
                return parseFunctionDeclaration(token);
            case RETURN:
                return parseReturnStatement(token);
            case YIELD:
                final Expression value = parseExpression();
                final Token end = expect(TokenType.SEMICOLON);
                return new YieldStatement(
                    value,
                    token.range().union(end.range())
                );
            case IF:
                final IfExpression conditional = parseIfExpression(token);
                return new ExpressionStatement(
                    conditional,
                    conditional.range()
                );
            case SWITCH:
                final SwitchExpression selection = parseSwitchExpression(token);
                return new ExpressionStatement(selection, selection.range());
            default:
                position--;
                return parseExpressionStatement();
        }
    }

    private BreakStatement parseBreakStatement(final Token keyword) {
        final Token semicolon = expect(TokenType.SEMICOLON);
        final Range range = keyword.range().union(semicolon.range());
        return new BreakStatement(range);
    }

    private ContinueStatement parseContinueStatement(final Token keyword) {
        final Token semicolon = expect(TokenType.SEMICOLON);
        final Range range = keyword.range().union(semicolon.range());
        return new ContinueStatement(range);
    }

    private ExpressionStatement parseExpressionStatement() {
        final Expression expression = parseExpression();
        final Token semicolon = expect(TokenType.SEMICOLON);
        final Range range = expression.range().union(semicolon.range());
        return new ExpressionStatement(expression, range);
    }

    private ClassDeclaration parseClassDeclaration(final Token keyword) {
        final Token name = expect(TokenType.IDENTIFIER);
        expect(TokenType.LEFT_BRACE);
        final List<@NonNull BlockItem> members = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            if (
                !check(TokenType.VAR) && !check(TokenType.CONST)
                    && !check(TokenType.FUNC)
                    && !check(TokenType.CONSTRUCTOR)
            ) {
                throw new ParserException(
                    current().range(),
                    "Class bodies may only contain fields, methods, and constructors"
                );
            }
            if (check(TokenType.VAR) || check(TokenType.CONST)) {
                final Token fieldKeyword = advance();
                members.add(
                    parseVariableDeclaration(
                        fieldKeyword,
                        fieldKeyword.type() == TokenType.VAR
                            ? Mutability.VAR
                            : Mutability.CONST,
                        true
                    )
                );
            }
            else {
                members.add(parseBlockItem());
            }
        }
        final Token close = expect(TokenType.RIGHT_BRACE);
        final Range range = keyword.range().union(close.range());
        final var id = new IdentifierDeclaration(name.text(), name.range());
        return new ClassDeclaration(id, members, range);
    }

    private ReturnStatement parseReturnStatement(final Token keyword) {
        final Expression value =
            check(TokenType.SEMICOLON) ? null : parseExpression();
        final Token semicolon = expect(TokenType.SEMICOLON);
        final Range range = keyword.range().union(semicolon.range());
        return new ReturnStatement(value, range);
    }

    private FunctionDeclaration parseFunctionDeclaration(final Token keyword) {
        final Token name = expect(TokenType.IDENTIFIER);
        final IdentifierDeclaration nameId =
            new IdentifierDeclaration(name.text(), name.range());
        expect(TokenType.LEFT_PAREN);
        final List<@NonNull FunctionParameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                final Token paramName = expect(TokenType.IDENTIFIER);
                expect(TokenType.COLON);
                final TypeNode type = parseType();
                final IdentifierDeclaration id =
                    new IdentifierDeclaration(
                        paramName.text(),
                        paramName.range()
                    );
                parameters.add(new FunctionParameter(id, type));
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RIGHT_PAREN);
        final TypeNode returnType =
            check(TokenType.LEFT_BRACE) ? null : parseType();
        final BlockStatement body = parseBlockStatement();
        final Range range = keyword.range().union(body.range());
        return new FunctionDeclaration(
            nameId,
            parameters,
            returnType,
            body,
            range
        );
    }

    private CallExpression parseCallExpression(final Expression callee) {
        expect(TokenType.LEFT_PAREN);
        final List<@NonNull Expression> arguments = new ArrayList<>();

        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                if (check(TokenType.IDENTIFIER) && check(1, TokenType.EQUAL)) {
                    final Token name = advance();
                    advance();
                    final Expression value = parseExpression();
                    arguments.add(
                        new NamedArgumentExpression(
                            new IdentifierDeclaration(
                                name.text(),
                                name.range()
                            ),
                            value
                        )
                    );
                }
                else {
                    arguments.add(parseExpression());
                }
            } while (match(TokenType.COMMA));
        }

        final Token close = expect(TokenType.RIGHT_PAREN);
        final Range range = callee.range().union(close.range());
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
        return check(TokenType.IDENTIFIER) && (check(1, TokenType.COLON)
            || (check(1, TokenType.COMMA) && check(2, TokenType.IDENTIFIER)
                && check(3, TokenType.COLON)));
    }

    private ForStatement parseCountedForStatement(final Token keyword) {
        final @Nullable Statement initializer;
        if (match(TokenType.SEMICOLON)) {
            initializer = null;
        }
        else if (check(TokenType.VAR) || check(TokenType.CONST)) {
            final Token declarationKeyword = advance();
            final Mutability mutability =
                declarationKeyword.type() == TokenType.VAR
                    ? Mutability.VAR
                    : Mutability.CONST;
            final Declaration declaration =
                parseVariableDeclaration(declarationKeyword, mutability);
            initializer = new DeclarationStatement(declaration);
        }
        else {
            initializer = parseExpressionStatement();
        }

        final @Nullable Expression condition =
            check(TokenType.SEMICOLON) ? null : parseExpression();
        expect(TokenType.SEMICOLON);
        final @Nullable Expression update =
            check(TokenType.RIGHT_PAREN) ? null : parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = keyword.range().union(body.range());
        return new ForStatement(initializer, condition, update, body, range);
    }

    private ForEachStatement parseForEachStatement(final Token keyword) {
        final Token value = expect(TokenType.IDENTIFIER);
        final Token index =
            match(TokenType.COMMA) ? expect(TokenType.IDENTIFIER) : null;
        final IdentifierDeclaration valueId =
            new IdentifierDeclaration(value.text(), value.range());
        final IdentifierDeclaration indexId =
            index != null
                ? new IdentifierDeclaration(index.text(), index.range())
                : null;
        expect(TokenType.COLON);
        final Expression iterable = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = keyword.range().union(body.range());
        return new ForEachStatement(valueId, indexId, iterable, body, range);
    }

    private WhileStatement parseWhileStatement(final Token keyword) {
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = keyword.range().union(body.range());
        return new WhileStatement(condition, body, range);
    }

    private DoWhileStatement parseDoWhileStatement(final Token keyword) {
        final BlockStatement body = parseBlockStatement();
        expect(TokenType.WHILE);
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final Token semicolon = expect(TokenType.SEMICOLON);
        final Range range = keyword.range().union(semicolon.range());
        return new DoWhileStatement(body, condition, range);
    }

    private SwitchExpression parseSwitchExpression(final Token keyword) {
        expect(TokenType.LEFT_PAREN);
        final Expression subject = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        expect(TokenType.LEFT_BRACE);
        final List<SwitchBranch> branches = new ArrayList<>();
        SwitchElseBranch elseBranch = null;
        while (!isAtEnd() && !check(TokenType.RIGHT_BRACE)) {
            final List<Expression> matches = new ArrayList<>();
            final Token start = current();
            final boolean isElseBranch = match(TokenType.ELSE);
            if (elseBranch != null) {
                throw new ParserException(
                    start.range(),
                    isElseBranch
                        ? "An else branch has already been defined"
                        : "The else branch must be last"
                );
            }
            if (!isElseBranch) {
                expect(TokenType.CASE);
                matches.add(parseExpression());
                while (match(TokenType.COMMA)) {
                    matches.add(parseExpression());
                }
            }
            final SwitchBranchBody body;
            if (match(TokenType.ARROW)) {
                final Expression value = parseExpression();
                body = new SwitchBranchExpressionBody(value);
            }
            else {
                final BlockStatement block = parseBlockStatement();
                body = new SwitchBranchBlockBody(block);
            }
            if (isElseBranch) {
                elseBranch =
                    new SwitchElseBranch(
                        body,
                        start.range().union(body.range())
                    );
            }
            else {
                branches.add(
                    new SwitchBranch(
                        matches,
                        body,
                        start.range().union(body.range())
                    )
                );
            }
        }
        final Token close = expect(TokenType.RIGHT_BRACE);
        return new SwitchExpression(
            subject,
            branches,
            elseBranch,
            keyword.range().union(close.range())
        );
    }

    private IfExpression parseIfExpression(final Token keyword) {
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
            final Range elifRange =
                elifKeyword.range().union(elifBranch.range());
            elifBranches
                .add(new ElseIfBranch(elifCondition, elifBranch, elifRange));
        }

        final BlockStatement elseBranch =
            match(TokenType.ELSE) ? parseBlockStatement() : null;

        final Range lastBranchRange =
            elseBranch != null
                ? elseBranch.range()
                : elifBranches.isEmpty()
                    ? thenBranch.range()
                    : Objects.requireNonNull(elifBranches.getLast()).range();
        final Range range = keyword.range().union(lastBranchRange);
        return new IfExpression(
            condition,
            thenBranch,
            elifBranches,
            elseBranch,
            range
        );
    }

    private Declaration parseVariableDeclaration(
        final Token keyword,
        final Mutability mutability
    ) {
        return parseVariableDeclaration(keyword, mutability, false);
    }

    private Declaration parseVariableDeclaration(
        final Token keyword,
        final Mutability mutability,
        final boolean field
    ) {
        final Token name = expect(TokenType.IDENTIFIER);
        final @Nullable TypeNode type =
            match(TokenType.COLON) ? parseType() : null;
        final IdentifierDeclaration identifier =
            new IdentifierDeclaration(name.text(), name.range());

        if (field && check(TokenType.SEMICOLON)) {
            if (type == null) {
                throw new ParserException(
                    identifier.range(),
                    "A field without a default requires an explicit type"
                );
            }
            final Token semicolon = advance();
            return new UninitializedVariableDeclaration(
                mutability,
                identifier,
                type,
                keyword.range().union(semicolon.range())
            );
        }
        expect(TokenType.EQUAL);
        final Expression initializer = parseExpression();
        final Token semicolon = expect(TokenType.SEMICOLON);
        final Range range = keyword.range().union(semicolon.range());
        return new VariableDeclaration(
            mutability,
            identifier,
            type,
            initializer,
            range
        );
    }

    private ConstructorDeclaration parseConstructorDeclaration(
        final Token keyword
    ) {
        expect(TokenType.LEFT_PAREN);
        final List<FunctionParameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                final Token name = expect(TokenType.IDENTIFIER);
                expect(TokenType.COLON);
                parameters.add(
                    new FunctionParameter(
                        new IdentifierDeclaration(name.text(), name.range()),
                        parseType()
                    )
                );
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        return new ConstructorDeclaration(
            parameters,
            body,
            keyword.range().union(body.range())
        );
    }

    private TypeNode parseType() {
        final List<TypeNode> members = new ArrayList<>();
        members.add(parseTypeMember());
        while (match(TokenType.PIPE)) {
            members.add(parseTypeMember());
        }
        return members.size() == 1
            ? members.get(0)
            : new UnionTypeNode(members);
    }

    private TypeNode parseTypeMember() {
        final Token open = matchToken(TokenType.LEFT_PAREN);
        if (open != null) {
            final List<TypeNode> elementTypes = new ArrayList<>();
            elementTypes.add(parseType());
            expect(TokenType.COMMA);
            do {
                elementTypes.add(parseType());
            } while (match(TokenType.COMMA));
            final Token close = expect(TokenType.RIGHT_PAREN);
            return new TupleTypeNode(
                elementTypes,
                open.range().union(close.range())
            );
        }
        final Token leftBracket = matchToken(TokenType.LEFT_BRACKET);
        if (leftBracket != null) {
            final TypeNode elementType = parseType();
            final Token rightBracket = expect(TokenType.RIGHT_BRACKET);
            return new ArrayTypeNode(
                elementType,
                leftBracket.range().union(rightBracket.range())
            );
        }
        final Token name =
            check(TokenType.NULL) ? advance() : expect(TokenType.IDENTIFIER);
        return new NamedTypeNode(name.text(), name.range());
    }

    private Expression parseExpression() {
        final Expression left = parseTernaryExpression();
        if (match(TokenType.EQUAL)) {
            final Expression value = parseExpression();
            return new AssignmentExpression(left, value);
        }
        return left;
    }

    private Expression parseTernaryExpression() {
        final Expression condition = parseBinaryExpression(1);
        if (!match(TokenType.QUESTION)) {
            return condition;
        }
        final Expression thenBranch = parseExpression();
        expect(TokenType.COLON);
        final Expression elseBranch = parseExpression();
        return new TernaryExpression(condition, thenBranch, elseBranch);
    }

    private Expression parseBinaryExpression(final int minimumPrecedence) {
        Expression left = parseUnaryExpression();
        while (!isAtEnd()) {
            final BinaryOperator operator =
                BINARY_OPERATORS.get(current().type());
            if (operator == null || precedence(operator) < minimumPrecedence) {
                break;
            }
            advance();
            final Expression right =
                parseBinaryExpression(precedence(operator) + 1);
            left = new BinaryExpression(left, operator, right);
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
            final Position end = tokens.getLast().range().end();
            throw new ParserException(
                new Range(end, end),
                "Expected expression, but reached end of input"
            );
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
            return new UnaryExpression(
                operator,
                operand,
                token.range().union(operand.range())
            );
        }
        Expression left = parsePrimitiveExpression(token);
        while (!isAtEnd()) {
            final Token next = current();
            final PostfixOperator postfix = POSTFIX_OPERATORS.get(next.type());
            if (postfix != null) {
                advance();
                left = parsePostfixExpression(left, postfix, next);
            }
            else if (match(TokenType.DOT)) {
                final Token name = expect(TokenType.IDENTIFIER);
                left =
                    new MemberExpression(
                        left,
                        new IdentifierExpression(name.text(), name.range()),
                        left.range().union(name.range())
                    );
            }
            else if (check(TokenType.LEFT_BRACKET)) {
                left = parseSubscriptExpression(left);
            }
            else if (check(TokenType.LEFT_PAREN)) {
                left = parseCallExpression(left);
            }
            else {
                break;
            }
        }
        return left;
    }

    private Expression parsePrimitiveExpression(final Token token) {
        return switch (token.type()) {
            case FORMAT_STRING_START -> parseFormatString(token);
            case THIS -> new ThisExpression(token.range());
            case IDENTIFIER ->
                new IdentifierExpression(token.text(), token.range());
            case BOOLEAN_LITERAL -> new LiteralExpression(
                LiteralKind.BOOL,
                token.text(),
                token.range()
            );
            case INTEGER_LITERAL -> new LiteralExpression(
                LiteralKind.INT,
                token.text(),
                token.range()
            );
            case FLOAT_LITERAL -> new LiteralExpression(
                LiteralKind.FLOAT,
                token.text(),
                token.range()
            );
            case STRING_LITERAL,
                RAW_STRING_LITERAL -> new LiteralExpression(
                    token.type() == TokenType.RAW_STRING_LITERAL
                        ? LiteralKind.RAW_STRING
                        : LiteralKind.STRING,
                    token.text(),
                    token.range()
                );
            case CHAR_LITERAL -> new LiteralExpression(
                LiteralKind.CHAR,
                token.text(),
                token.range()
            );
            case NULL -> new LiteralExpression(
                LiteralKind.NULL,
                token.text(),
                token.range()
            );
            case NEW -> {
                final Token name = expect(TokenType.IDENTIFIER);
                final IdentifierExpression className =
                    new IdentifierExpression(name.text(), name.range());
                final CallExpression call = parseCallExpression(className);
                yield new NewExpression(
                    className,
                    call.arguments(),
                    token.range().union(call.range())
                );
            }
            case IF -> parseIfExpression(token);
            case SWITCH -> parseSwitchExpression(token);
            case LEFT_PAREN -> {
                if (isLambdaAfterOpenParen()) {
                    yield parseLambdaExpression(token);
                }
                else {
                    yield parseGroupingExpression(token);
                }
            }
            case LEFT_BRACKET -> parseArrayExpression(token);
            default -> throw new ParserException(
                token.range(),
                "Expected expression, but found %s",
                token.type()
            );
        };
    }

    private Expression parseFormatString(final Token start) {
        final List<Expression> parts = new ArrayList<>();
        while (!check(TokenType.FORMAT_STRING_END)) {
            if (match(TokenType.LEFT_BRACE)) {
                parts.add(parseExpression());
                expect(TokenType.RIGHT_BRACE);
            }
            else {
                final Token part =
                    check(TokenType.RAW_STRING_LITERAL)
                        ? expect(TokenType.RAW_STRING_LITERAL)
                        : expect(TokenType.STRING_LITERAL);
                parts.add(parsePrimitiveExpression(part));
            }
        }
        final Token end = expect(TokenType.FORMAT_STRING_END);
        return new FormatStringExpression(
            parts,
            start.range().union(end.range())
        );
    }

    private Expression parseGroupingExpression(final Token open) {
        final Expression expression = parseExpression();
        if (match(TokenType.COMMA)) {
            final List<Expression> elements = new ArrayList<>();
            elements.add(expression);
            do {
                elements.add(parseExpression());
            } while (match(TokenType.COMMA));
            final Token close = expect(TokenType.RIGHT_PAREN);
            return new TupleExpression(
                elements,
                open.range().union(close.range())
            );
        }
        final Token close = expect(TokenType.RIGHT_PAREN);
        final Range range = open.range().union(close.range());
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
        final Range range = open.range().union(close.range());
        return new ArrayExpression(elements, range);
    }

    private Expression parseSubscriptExpression(final Expression target) {
        expect(TokenType.LEFT_BRACKET);
        boolean isSlice = false;
        Expression startIndex, endIndex;

        // [:] or [:i]
        if (match(TokenType.COLON)) {
            isSlice = true;
            startIndex = null;
            endIndex =
                check(TokenType.RIGHT_BRACKET) ? null : parseExpression();
        }
        // [i:] or [i:j]
        else {
            startIndex = parseExpression();
            if (match(TokenType.COLON)) {
                isSlice = true;
                endIndex =
                    check(TokenType.RIGHT_BRACKET) ? null : parseExpression();
            }
            else {
                endIndex = null;
            }
        }

        final Token rightBracket = expect(TokenType.RIGHT_BRACKET);
        final Range range = target.range().union(rightBracket.range());
        if (isSlice) {
            return new SliceExpression(target, startIndex, endIndex, range);
        }
        return new SubscriptExpression(
            target,
            Objects.requireNonNull(startIndex),
            range
        );
    }

    // The opening parenthesis has already been consumed. Lookahead leaves position
    // unchanged.
    private boolean isLambdaAfterOpenParen() {
        int depth = 1;
        for (int offset = 0; position + offset < tokens.size(); offset++) {
            final TokenType type =
                Objects.requireNonNull(tokens.get(position + offset)).type();
            if (type == TokenType.LEFT_PAREN) {
                depth++;
            }
            else if (type == TokenType.RIGHT_PAREN && --depth == 0) {
                return check(offset + 1, TokenType.ARROW)
                    || (check(offset + 1, TokenType.IDENTIFIER)
                        && check(offset + 2, TokenType.ARROW));
            }
        }
        return false;
    }

    private LambdaExpression parseLambdaExpression(final Token open) {
        final List<@NonNull LambdaParameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                final Token name = expect(TokenType.IDENTIFIER);
                final IdentifierDeclaration id =
                    new IdentifierDeclaration(name.text(), name.range());
                parameters.add(new LambdaParameter(id));
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RIGHT_PAREN);
        expect(TokenType.ARROW);
        final AstNode body =
            check(TokenType.LEFT_BRACE)
                ? parseBlockStatement()
                : parseExpression();
        final Range range = open.range().union(body.range());
        return new LambdaExpression(parameters, body, range);
    }

    private PostfixExpression parsePostfixExpression(
        final Expression operand,
        final PostfixOperator operator,
        final Token operatorToken
    ) {
        final Range range = operand.range().union(operatorToken.range());
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
            && Objects.requireNonNull(tokens.get(position + offset))
                .type() == type;
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
            final Position position = tokens.getLast().range().end();
            throw new ParserException(
                new Range(position, position),
                "Expected %s, but reached end of input",
                type
            );
        }

        final Token token = current();

        if (token.type() != type) {
            throw new ParserException(
                token.range(),
                "Expected %s, but found %s",
                type,
                token.type()
            );
        }

        advance();

        return token;
    }
}
