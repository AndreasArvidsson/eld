package com.github.andreasarvidsson.eld.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
                Map.entry(TokenType.INSTANCEOF, BinaryOperator.INSTANCEOF),
                Map.entry(TokenType.LESS, BinaryOperator.LESS),
                Map.entry(TokenType.GREATER, BinaryOperator.GREATER),
                Map.entry(TokenType.AND, BinaryOperator.AND),
                Map.entry(TokenType.OR, BinaryOperator.OR),
                Map.entry(TokenType.BIT_AND, BinaryOperator.BIT_AND),
                Map.entry(TokenType.PIPE, BinaryOperator.BIT_OR),
                Map.entry(TokenType.BIT_XOR, BinaryOperator.BIT_XOR)
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
        return parseTernaryExpression();
    }

    public CallExpression parseCallExpression(final Expression callee) {
        expect(TokenType.LEFT_PAREN);
        final List<@NonNull Expression> arguments = new ArrayList<>();

        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                if (check(TokenType.IDENTIFIER) && check(1, TokenType.COLON)) {
                    final Token name = advance();
                    if (name.text().equals("_")) {
                        throw new ParserException(
                            name.range(),
                            "A discard parameter cannot be named"
                        );
                    }
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
            final BinaryOperator operator = binaryOperator();
            if (operator == null || precedence(operator) < minimumPrecedence) {
                break;
            }
            final int tokens =
                operator == BinaryOperator.UNSIGNED_SHIFT_RIGHT
                    ? 3
                    : isShift(operator) || operator == BinaryOperator.LESS_EQUAL
                        || operator == BinaryOperator.GREATER_EQUAL ? 2 : 1;
            for (int i = 0; i < tokens; i++) {
                advance();
            }
            final Expression right =
                parseBinaryExpression(precedence(operator) + 1);
            left = new BinaryExpression(left, operator, right);
        }
        return left;
    }

    private @Nullable BinaryOperator binaryOperator() {
        if (
            angleSequence(TokenType.GREATER, 3, true)
                || angleSequence(TokenType.GREATER, 2, true)
                || angleSequence(TokenType.LESS, 2, true)
        ) {
            return null;
        }
        if (angleSequence(TokenType.GREATER, 3, false)) {
            return BinaryOperator.UNSIGNED_SHIFT_RIGHT;
        }
        if (angleSequence(TokenType.GREATER, 2, false)) {
            return BinaryOperator.SHIFT_RIGHT;
        }
        if (angleSequence(TokenType.LESS, 2, false)) {
            return BinaryOperator.SHIFT_LEFT;
        }
        if (angleSequence(TokenType.LESS, 1, true)) {
            return BinaryOperator.LESS_EQUAL;
        }
        if (angleSequence(TokenType.GREATER, 1, true)) {
            return BinaryOperator.GREATER_EQUAL;
        }
        return BINARY_OPERATORS.get(current().type());
    }

    private static boolean isShift(final BinaryOperator operator) {
        return operator == BinaryOperator.SHIFT_LEFT
            || operator == BinaryOperator.SHIFT_RIGHT
            || operator == BinaryOperator.UNSIGNED_SHIFT_RIGHT;
    }

    private static int precedence(final BinaryOperator operator) {
        return switch (operator) {
            case OR -> 1;
            case AND -> 2;
            case BIT_OR -> 3;
            case BIT_XOR -> 4;
            case BIT_AND -> 5;
            case EQUAL, NOT_EQUAL -> 6;
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL, INSTANCEOF -> 7;
            case SHIFT_LEFT, SHIFT_RIGHT, UNSIGNED_SHIFT_RIGHT -> 8;
            case ADD, SUBTRACT -> 9;
            case MULTIPLY, DIVIDE, MODULO -> 10;
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
            case TILDE -> UnaryOperator.BIT_NOT;
            default -> null;
        };
        if (token.type() == TokenType.AWAIT) {
            final Expression operand = parseUnaryExpression();
            return new AwaitExpression(
                operand,
                token.range().union(operand.range())
            );
        }
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
            case LEFT_BRACE -> parseMapExpression(token);
            default -> throw ParserException.expected("expression", token);
        };
    }

    private Expression parseFormatString(final Token start) {
        final List<Expression> parts = new ArrayList<>();
        final boolean raw =
            start.text().startsWith("r") || start.text().startsWith("fr");
        while (!check(TokenType.FORMAT_STRING_END)) {
            if (match(TokenType.LEFT_BRACE)) {
                parts.add(parseExpression());
                expect(TokenType.RIGHT_BRACE);
            }
            else {
                final Token part = expect(TokenType.FORMAT_STRING_TEXT);
                parts.add(
                    new LiteralExpression(
                        raw
                            ? LiteralKind.RAW_FORMAT_STRING_TEXT
                            : LiteralKind.FORMAT_STRING_TEXT,
                        part.text(),
                        part.range()
                    )
                );
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

    private MapExpression parseMapExpression(final Token open) {
        final List<@NonNull MapElement> elements = new ArrayList<>();
        if (!check(TokenType.RIGHT_BRACE)) {
            do {
                if (check(TokenType.RIGHT_BRACE)) {
                    break;
                }
                if (check(TokenType.ELLIPSIS)) {
                    final Token spread = advance();
                    final Expression expression = parseExpression();
                    elements.add(
                        new MapSpread(
                            expression,
                            spread.range().union(expression.range())
                        )
                    );
                }
                else {
                    final Expression key = parseExpression();
                    expect(TokenType.COLON);
                    final Expression value = parseExpression();
                    elements.add(
                        new MapEntry(
                            key,
                            value,
                            key.range().union(value.range())
                        )
                    );
                }
            } while (match(TokenType.COMMA));
        }
        final Token close = expect(TokenType.RIGHT_BRACE);
        return new MapExpression(elements, open.range().union(close.range()));
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
                Parser.assertIdentifierCase(name, "parameter");
                parameters.add(
                    new LambdaParameter(
                        name.text().equals("_")
                            ? new DiscardDeclaration(name.range())
                            : new IdentifierDeclaration(
                                name.text(),
                                name.range()
                            )
                    )
                );
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
