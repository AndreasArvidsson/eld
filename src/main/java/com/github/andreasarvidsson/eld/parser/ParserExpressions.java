package com.github.andreasarvidsson.eld.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.lexer.Token;
import com.github.andreasarvidsson.eld.lexer.TokenType;

public class ParserExpressions extends ParserBase {
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

    private final Parser parser;

    public ParserExpressions(final TokenStream tokens, final Parser parser) {
        super(tokens);
        this.parser = parser;
    }

    public Expression parseExpression() {
        final Expression left = parseTernaryExpression();
        if (match(TokenType.EQUAL)) {
            final Expression value = parseExpression();
            return new AssignmentExpression(left, value);
        }
        return left;
    }

    public CallExpression parseCallExpression(final Expression callee) {
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

    public SwitchExpression parseSwitchExpression(final Token keyword) {
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
                final BlockStatement block = parser.parseBlockStatement();
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

    public IfExpression parseIfExpression(final Token keyword) {
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement thenBranch = parser.parseBlockStatement();
        final List<@NonNull ElseIfBranch> elifBranches = new ArrayList<>();

        while (check(TokenType.ELIF)) {
            final Token elifKeyword = expect(TokenType.ELIF);
            expect(TokenType.LEFT_PAREN);
            final Expression elifCondition = parseExpression();
            expect(TokenType.RIGHT_PAREN);
            final BlockStatement elifBranch = parser.parseBlockStatement();
            final Range elifRange =
                elifKeyword.range().union(elifBranch.range());
            elifBranches
                .add(new ElseIfBranch(elifCondition, elifBranch, elifRange));
        }

        final BlockStatement elseBranch =
            match(TokenType.ELSE) ? parser.parseBlockStatement() : null;

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
            throw ParserException.expectedEof(getLastPosition(), "expression");
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
                final List<TypeNode> typeArguments =
                    parser.parseTypeArguments();
                final CallExpression call = parseCallExpression(className);
                yield new NewExpression(
                    className,
                    typeArguments,
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
            case LEFT_BRACE -> parseObjectExpression(token);
            default -> throw ParserException.expected("expression", token);
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
                if (check(TokenType.RIGHT_BRACKET)) {
                    break;
                }
                if (check(TokenType.ELLIPSIS)) {
                    final Token spread = advance();
                    final Expression value = parseExpression();
                    elements.add(
                        new ArraySpread(
                            value,
                            spread.range().union(value.range())
                        )
                    );
                }
                else {
                    elements.add(parseExpression());
                }
            } while (match(TokenType.COMMA));
        }
        final Token close = expect(TokenType.RIGHT_BRACKET);
        final Range range = open.range().union(close.range());
        return new ArrayExpression(elements, range);
    }

    private ObjectExpression parseObjectExpression(final Token open) {
        final List<@NonNull ObjectEntry> members = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            if (check(TokenType.ELLIPSIS)) {
                final Token spread = advance();
                final Expression value = parseExpression();
                members.add(
                    new ObjectSpread(value, spread.range().union(value.range()))
                );
            }
            else {
                final Token name = expect(TokenType.IDENTIFIER);
                expect(TokenType.COLON);
                final Expression value = parseExpression();
                members.add(
                    new ObjectMember(
                        new IdentifierDeclaration(name.text(), name.range()),
                        value,
                        name.range().union(value.range())
                    )
                );
            }
            if (!match(TokenType.COMMA)) {
                break;
            }
        }
        final Token close = expect(TokenType.RIGHT_BRACE);
        return new ObjectExpression(members, open.range().union(close.range()));
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
        for (int offset = 0; !isAtEnd(offset); offset++) {
            final TokenType type = peek(offset).type();
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
                ? parser.parseBlockStatement()
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
}
