package com.github.andreasarvidsson.eld.parser;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.lexer.Token;
import com.github.andreasarvidsson.eld.lexer.TokenType;

public final class Parser extends ParserBase {
    private final ParserExpressions parserExpressions;

    public Parser(final List<@NonNull Token> tokens) {
        final TokenStream tokenStream = new TokenStream(tokens);
        super(tokenStream);
        this.parserExpressions = new ParserExpressions(tokenStream, this);
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

    BlockStatement parseBlockStatement() {
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
            case RECORD:
                return parseRecordDeclaration(token);
            case INTERFACE:
                return parseInterfaceDeclaration(token);
            case CONSTRUCTOR:
                return parseConstructorDeclaration(token);
            case SUPER:
                final CallExpression superCall =
                    parserExpressions.parseCallExpression(
                        new IdentifierExpression(token.text(), token.range())
                    );
                final Token superEnd = expect(TokenType.SEMICOLON);
                return new SuperConstructorCall(
                    superCall.arguments(),
                    token.range().union(superEnd.range())
                );
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
            case TRY:
                return parseTryStatement(token);
            case THROW:
                final Expression thrown = parserExpressions.parseExpression();
                final Token throwEnd = expect(TokenType.SEMICOLON);
                return new ThrowStatement(
                    thrown,
                    token.range().union(throwEnd.range())
                );
            case RETURN:
                return parseReturnStatement(token);
            case YIELD:
                final Expression value = parserExpressions.parseExpression();
                final Token end = expect(TokenType.SEMICOLON);
                return new YieldStatement(
                    value,
                    token.range().union(end.range())
                );
            case IF:
                final IfExpression conditional =
                    parserExpressions.parseIfExpression(token);
                return new ExpressionStatement(
                    conditional,
                    conditional.range()
                );
            case SWITCH:
                final SwitchExpression selection =
                    parserExpressions.parseSwitchExpression(token);
                return new ExpressionStatement(selection, selection.range());
            default:
                goBack();
                return parseExpressionStatement();
        }
    }

    private TryStatement parseTryStatement(final Token keyword) {
        final BlockStatement body = parseBlockStatement();
        final List<CatchClause> catches = new ArrayList<>();
        Range range = keyword.range().union(body.range());
        while (match(TokenType.CATCH)) {
            final Token catchKeyword = peek(-1);
            expect(TokenType.LEFT_PAREN);
            final Token name = expect(TokenType.IDENTIFIER);
            expect(TokenType.COLON);
            final TypeNode type = parseType();
            expect(TokenType.RIGHT_PAREN);
            final BlockStatement catchBody = parseBlockStatement();
            catches.add(
                new CatchClause(
                    new IdentifierDeclaration(name.text(), name.range()),
                    type,
                    catchBody,
                    catchKeyword.range().union(catchBody.range())
                )
            );
            range = range.union(catchBody.range());
        }
        final BlockStatement finallyBody;
        if (match(TokenType.FINALLY)) {
            finallyBody = parseBlockStatement();
            range = range.union(finallyBody.range());
        }
        else {
            finallyBody = null;
        }
        if (catches.isEmpty() && finallyBody == null) {
            throw new ParserException(
                keyword.range(),
                "A try statement requires catch or finally"
            );
        }
        return new TryStatement(body, List.copyOf(catches), finallyBody, range);
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
        final Expression expression = parserExpressions.parseExpression();
        final Token semicolon = expect(TokenType.SEMICOLON);
        final Range range = expression.range().union(semicolon.range());
        return new ExpressionStatement(expression, range);
    }

    private ClassDeclaration parseClassDeclaration(final Token keyword) {
        final Token name = expect(TokenType.IDENTIFIER);
        final IdentifierExpression superclass;
        if (match(TokenType.EXTENDS)) {
            final Token base = expect(TokenType.IDENTIFIER);
            superclass = new IdentifierExpression(base.text(), base.range());
        }
        else {
            superclass = null;
        }
        final List<@NonNull TypeNode> implementedInterfaces = new ArrayList<>();
        if (match(TokenType.IMPLEMENTS)) {
            do {
                implementedInterfaces.add(parseType());
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.LEFT_BRACE);
        final List<@NonNull MemberDeclaration> members = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            final Token modifier =
                check(TokenType.PUBLIC) || check(TokenType.PROTECTED)
                    ? advance()
                    : null;
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
            final Declaration member;
            if (check(TokenType.VAR) || check(TokenType.CONST)) {
                final Token fieldKeyword = advance();
                member =
                    parseVariableDeclaration(
                        fieldKeyword,
                        fieldKeyword.type() == TokenType.VAR
                            ? Mutability.VAR
                            : Mutability.CONST,
                        true
                    );
            }
            else {
                member =
                    check(TokenType.FUNC)
                        ? parseFunctionDeclaration(advance())
                        : parseConstructorDeclaration(advance());
            }
            members.add(
                new MemberDeclaration(
                    modifier == null
                        ? Visibility.PRIVATE
                        : modifier.type() == TokenType.PROTECTED
                            ? Visibility.PROTECTED
                            : Visibility.PUBLIC,
                    member,
                    modifier != null
                        ? modifier.range().union(member.range())
                        : member.range()
                )
            );
        }
        final Token close = expect(TokenType.RIGHT_BRACE);
        final Range range = keyword.range().union(close.range());
        final var id = new IdentifierDeclaration(name.text(), name.range());
        return new ClassDeclaration(
            id,
            superclass,
            implementedInterfaces,
            members,
            range
        );
    }

    private RecordDeclaration parseRecordDeclaration(final Token keyword) {
        final Token name = expect(TokenType.IDENTIFIER);
        final IdentifierDeclaration recordName =
            new IdentifierDeclaration(name.text(), name.range());
        expect(TokenType.LEFT_PAREN);
        final List<RecordParameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                final RecordParameter parameter = parseRecordParameter();
                if (parameter.name().name().equals("copy")) {
                    throw new ParserException(
                        parameter.name().range(),
                        "Record parameter name 'copy' is reserved"
                    );
                }
                parameters.add(parameter);
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RIGHT_PAREN);
        final List<@NonNull TypeNode> implementedInterfaces = new ArrayList<>();
        if (match(TokenType.IMPLEMENTS)) {
            do {
                implementedInterfaces.add(parseType());
            } while (match(TokenType.COMMA));
        }
        final List<@NonNull MemberDeclaration> methods = new ArrayList<>();
        final Token end;
        if (match(TokenType.SEMICOLON)) {
            end = peek(-1);
        }
        else {
            expect(TokenType.LEFT_BRACE);
            while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
                final Token modifier =
                    check(TokenType.PUBLIC) || check(TokenType.PROTECTED)
                        ? advance()
                        : null;
                if (!check(TokenType.FUNC)) {
                    throw new ParserException(
                        current().range(),
                        "Record bodies may only contain methods"
                    );
                }
                final FunctionDeclaration method =
                    parseFunctionDeclaration(advance());
                if (method.name().name().equals("copy")) {
                    throw new ParserException(
                        method.name().range(),
                        "Record method name 'copy' is reserved"
                    );
                }
                methods.add(
                    new MemberDeclaration(
                        modifier == null
                            ? Visibility.PRIVATE
                            : modifier.type() == TokenType.PROTECTED
                                ? Visibility.PROTECTED
                                : Visibility.PUBLIC,
                        method,
                        modifier == null
                            ? method.range()
                            : modifier.range().union(method.range())
                    )
                );
            }
            end = expect(TokenType.RIGHT_BRACE);
        }
        return new RecordDeclaration(
            recordName,
            List.copyOf(parameters),
            implementedInterfaces,
            methods,
            keyword.range().union(end.range())
        );
    }

    private RecordParameter parseRecordParameter() {
        final Token name = expect(TokenType.IDENTIFIER);
        if (match(TokenType.QUESTION)) {
            throw new ParserException(
                peek(-1).range(),
                "Record parameters cannot be optional"
            );
        }
        expect(TokenType.COLON);
        final TypeNode type = parseType();
        if (check(TokenType.EQUAL)) {
            throw new ParserException(
                current().range(),
                "Record parameters cannot have default values"
            );
        }
        return new RecordParameter(
            new IdentifierDeclaration(name.text(), name.range()),
            type
        );
    }

    private InterfaceDeclaration parseInterfaceDeclaration(
        final Token keyword
    ) {
        final Token name = expect(TokenType.IDENTIFIER);
        final List<@NonNull TypeNode> parents = new ArrayList<>();
        if (match(TokenType.EXTENDS)) {
            do {
                parents.add(parseType());
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.LEFT_BRACE);
        final List<@NonNull InterfaceMemberDeclaration> members =
            new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !isAtEnd()) {
            if (check(TokenType.FUNC)) {
                members.add(parseInterfaceMethod());
            }
            else if (check(TokenType.CONST) || check(TokenType.VAR)) {
                members.add(parseInterfaceVariable());
            }
            else {
                throw ParserException.unexpected(current());
            }
        }
        final Token end = expect(TokenType.RIGHT_BRACE);
        return new InterfaceDeclaration(
            new IdentifierDeclaration(name.text(), name.range()),
            parents,
            members,
            keyword.range().union(end.range())
        );
    }

    private InterfaceMethodDeclaration parseInterfaceMethod() {
        final Token keyword = expect(TokenType.FUNC);
        final Token name = expect(TokenType.IDENTIFIER);
        final IdentifierDeclaration id =
            new IdentifierDeclaration(name.text(), name.range());
        expect(TokenType.LEFT_PAREN);
        final List<@NonNull FunctionParameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                final FunctionParameter parameter = parseFunctionParameter();
                if (parameter.defaultValue() != null) {
                    throw new ParserException(
                        parameter.defaultValue().range(),
                        "Interface parameters cannot have default values"
                    );
                }
                parameters.add(parameter);
            } while (match(TokenType.COMMA));
        }
        expect(TokenType.RIGHT_PAREN);
        final TypeNode returnType =
            check(TokenType.SEMICOLON) ? null : parseType();
        final Token end = expect(TokenType.SEMICOLON);
        return new InterfaceMethodDeclaration(
            id,
            parameters,
            returnType,
            keyword.range().union(end.range())
        );
    }

    private UninitializedVariableDeclaration parseInterfaceVariable() {
        final Mutability mutability =
            check(TokenType.CONST) ? Mutability.CONST : Mutability.VAR;
        final Token keyword =
            expect(
                mutability == Mutability.CONST ? TokenType.CONST : TokenType.VAR
            );
        if (check(TokenType.FUNC)) {
            throw new ParserException(
                current().range(),
                "Interface methods cannot have a const or var modifier"
            );
        }
        final Token name = expect(TokenType.IDENTIFIER);
        final IdentifierDeclaration id =
            new IdentifierDeclaration(name.text(), name.range());
        expect(TokenType.COLON);
        final TypeNode type = parseType();
        final Token end = expect(TokenType.SEMICOLON);
        return new UninitializedVariableDeclaration(
            mutability,
            id,
            type,
            keyword.range().union(end.range())
        );
    }

    private ReturnStatement parseReturnStatement(final Token keyword) {
        final Expression value =
            check(TokenType.SEMICOLON)
                ? null
                : parserExpressions.parseExpression();
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
                parameters.add(parseFunctionParameter());
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
            check(TokenType.SEMICOLON)
                ? null
                : parserExpressions.parseExpression();
        expect(TokenType.SEMICOLON);
        final @Nullable Expression update =
            check(TokenType.RIGHT_PAREN)
                ? null
                : parserExpressions.parseExpression();
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
        final Expression iterable = parserExpressions.parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = keyword.range().union(body.range());
        return new ForEachStatement(valueId, indexId, iterable, body, range);
    }

    private WhileStatement parseWhileStatement(final Token keyword) {
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parserExpressions.parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final BlockStatement body = parseBlockStatement();
        final Range range = keyword.range().union(body.range());
        return new WhileStatement(condition, body, range);
    }

    private DoWhileStatement parseDoWhileStatement(final Token keyword) {
        final BlockStatement body = parseBlockStatement();
        expect(TokenType.WHILE);
        expect(TokenType.LEFT_PAREN);
        final Expression condition = parserExpressions.parseExpression();
        expect(TokenType.RIGHT_PAREN);
        final Token semicolon = expect(TokenType.SEMICOLON);
        final Range range = keyword.range().union(semicolon.range());
        return new DoWhileStatement(body, condition, range);
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
        final Expression initializer = parserExpressions.parseExpression();
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

    private FunctionParameter parseFunctionParameter() {
        final Token name = expect(TokenType.IDENTIFIER);
        final boolean optional = match(TokenType.QUESTION);
        expect(TokenType.COLON);
        final TypeNode type = parseType();
        final Expression defaultValue =
            match(TokenType.EQUAL) ? parserExpressions.parseExpression() : null;
        return new FunctionParameter(
            new IdentifierDeclaration(name.text(), name.range()),
            type,
            optional,
            defaultValue
        );
    }

    private ConstructorDeclaration parseConstructorDeclaration(
        final Token keyword
    ) {
        expect(TokenType.LEFT_PAREN);
        final List<FunctionParameter> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                parameters.add(parseFunctionParameter());
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
            if (!check(TokenType.RIGHT_PAREN)) {
                do {
                    elementTypes.add(parseType());
                } while (match(TokenType.COMMA));
            }
            final Token close = expect(TokenType.RIGHT_PAREN);
            final Token arrow = matchToken(TokenType.ARROW);
            if (arrow != null) {
                final TypeNode returnType =
                    check(TokenType.IDENTIFIER) || check(TokenType.NULL)
                        || check(TokenType.LEFT_PAREN)
                        || check(TokenType.LEFT_BRACKET) ? parseType() : null;
                return new FunctionTypeNode(
                    elementTypes,
                    returnType,
                    open.range()
                        .union(
                            returnType != null
                                ? returnType.range()
                                : arrow.range()
                        )
                );
            }
            if (elementTypes.size() < 2) {
                throw new ParserException(
                    open.range().union(close.range()),
                    "A tuple type requires at least two elements; function types require '=>'"
                );
            }
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
        final List<TypeNode> arguments = parseTypeArguments();
        TypeNode result =
            new NamedTypeNode(
                name.text(),
                arguments,
                name.range().union(peek(-1).range())
            );
        while (match(TokenType.LEFT_BRACKET)) {
            final Token close = expect(TokenType.RIGHT_BRACKET);
            result =
                new ArrayTypeNode(result, result.range().union(close.range()));
        }
        return result;
    }

    List<TypeNode> parseTypeArguments() {
        if (!match(TokenType.LESS)) {
            return List.of();
        }
        final List<TypeNode> arguments = new ArrayList<>();
        do {
            arguments.add(parseType());
        } while (match(TokenType.COMMA));
        expect(TokenType.GREATER);
        return List.copyOf(arguments);
    }

}
