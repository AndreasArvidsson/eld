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
            items.add(parseTopLevelItem());
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
            items.add(parseStatement());
        }

        final Token close = expect(TokenType.RIGHT_BRACE);
        final Range range = open.range().union(close.range());

        return new BlockStatement(items, range);
    }

    private BlockItem parseTopLevelItem() {
        if (
            (check(TokenType.FINAL) || check(TokenType.OVERRIDE)
                || check(TokenType.ASYNC)) && !functionDeclarationStartsHere()
        ) {
            throw ParserException.expected("top-level declaration", current());
        }
        return switch (current().type()) {
            case CONST, VAR, TYPE, CLASS, RECORD, INTERFACE, FUNC, FINAL,
                OVERRIDE, ASYNC -> parseTopLevelDeclaration();
            case PUBLIC, PROTECTED -> throw ParserException
                .expected("top-level declaration", current());
            case STATIC -> throw new ParserException(
                current().range(),
                "'static' is only allowed on class members"
            );
            default -> parseStatement();
        };
    }

    private Declaration parseTopLevelDeclaration() {
        final Token token = advance();
        return switch (token.type()) {
            case CONST -> functionFollows()
                ? parseModifiedFunctionDeclaration(token)
                : parseVariableDeclaration(token, Mutability.CONST);
            case VAR -> parseVariableDeclaration(token, Mutability.VAR);
            case TYPE -> parseTypeAliasDeclaration(token);
            case CLASS -> parseClassDeclaration(token);
            case RECORD -> parseRecordDeclaration(token);
            case INTERFACE -> parseInterfaceDeclaration(token);
            case FUNC -> parseFunctionDeclaration(token, false, List.of());
            case FINAL, OVERRIDE, ASYNC ->
                parseModifiedFunctionDeclaration(token);
            default -> throw new IllegalStateException(
                "Unexpected top-level declaration token: " + token.type()
            );
        };
    }

    private BlockItem parseStatement() {
        final Token token = advance();

        switch (token.type()) {
            case CONST:
                if (functionFollows()) {
                    throw ParserException.expected("statement", token);
                }
                return parseVariableDeclaration(token, Mutability.CONST);
            case VAR:
                return parseVariableDeclaration(token, Mutability.VAR);
            case TYPE, CLASS, RECORD, INTERFACE, CONSTRUCTOR, FUNC, FINAL,
                OVERRIDE, ASYNC, PUBLIC, PROTECTED:
                throw ParserException.expected("statement", token);
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
            case IGNORE:
                final Expression ignored = parserExpressions.parseExpression();
                final Token ignoreEnd = expect(TokenType.SEMICOLON);
                return new IgnoreStatement(
                    ignored,
                    token.range().union(ignoreEnd.range())
                );
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
                if (destructuringAssignmentFollows()) {
                    return parseDestructuringAssignment();
                }
                return parseExpressionStatement();
        }
    }

    private TypeAliasDeclaration parseTypeAliasDeclaration(
        final Token keyword
    ) {
        final Token name = expect(TokenType.IDENTIFIER);
        expect(TokenType.EQUAL);
        final TypeNode type = parseType();
        final Token semicolon = expect(TokenType.SEMICOLON);
        return new TypeAliasDeclaration(
            new IdentifierDeclaration(name.text(), name.range()),
            type,
            keyword.range().union(semicolon.range())
        );
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
            members.add(parseClassMember());
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

    private MemberDeclaration parseClassMember() {
        final Token modifier =
            check(TokenType.PUBLIC) || check(TokenType.PROTECTED)
                ? advance()
                : null;
        final Token staticModifier = match(TokenType.STATIC) ? peek(-1) : null;
        if (staticModifier != null && check(TokenType.LEFT_BRACE)) {
            if (modifier != null) {
                throw new ParserException(
                    modifier.range().union(staticModifier.range()),
                    "'%s' is not allowed on static initializers",
                    modifier.text()
                );
            }
            final BlockStatement body = parseBlockStatement();
            final Range range = staticModifier.range().union(body.range());
            return new MemberDeclaration(
                Visibility.PRIVATE,
                true,
                new StaticInitializerDeclaration(body, range),
                range
            );
        }
        int constructorOffset = 0;
        while (
            check(constructorOffset, TokenType.CONST)
                || check(constructorOffset, TokenType.FINAL)
                || check(constructorOffset, TokenType.OVERRIDE)
                || check(constructorOffset, TokenType.ASYNC)
        ) {
            constructorOffset++;
        }
        if (
            constructorOffset > 0
                && check(constructorOffset, TokenType.CONSTRUCTOR)
        ) {
            final Token invalidModifier = current();
            throw new ParserException(
                invalidModifier.range().union(peek(constructorOffset).range()),
                "'%s' is not allowed on constructors",
                invalidModifier.text()
            );
        }
        if (staticModifier != null && check(TokenType.CONSTRUCTOR)) {
            throw new ParserException(
                staticModifier.range().union(current().range()),
                "'static' is not allowed on constructors"
            );
        }
        final boolean function = functionDeclarationStartsHere();
        if (
            !check(TokenType.VAR) && !(check(TokenType.CONST) && !function)
                && !function
                && !check(TokenType.CONSTRUCTOR)
        ) {
            throw ParserException.expected("class member", current());
        }
        final Declaration member;
        if (check(TokenType.VAR) || (check(TokenType.CONST) && !function)) {
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
                function
                    ? parseModifiedFunctionDeclaration(advance())
                    : parseConstructorDeclaration(advance());
        }
        final Range range =
            modifier != null
                ? modifier.range().union(member.range())
                : staticModifier != null
                    ? staticModifier.range().union(member.range())
                    : member.range();
        return new MemberDeclaration(
            modifier == null
                ? Visibility.PRIVATE
                : modifier.type() == TokenType.PROTECTED
                    ? Visibility.PROTECTED
                    : Visibility.PUBLIC,
            staticModifier != null,
            member,
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
                methods.add(parseRecordMember());
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

    private MemberDeclaration parseRecordMember() {
        final Token modifier =
            check(TokenType.PUBLIC) || check(TokenType.PROTECTED)
                ? advance()
                : null;
        final Token finalModifier = functionModifier(TokenType.FINAL);
        if (finalModifier != null) {
            throw new ParserException(
                finalModifier.range().union(functionKeyword().range()),
                "'final' is not allowed on record methods"
            );
        }
        if (!functionDeclarationStartsHere()) {
            throw ParserException.expected("record member", current());
        }
        final FunctionDeclaration method =
            parseModifiedFunctionDeclaration(advance());
        if (method.name().name().equals("copy")) {
            throw new ParserException(
                method.name().range(),
                "Record method name 'copy' is reserved"
            );
        }
        return new MemberDeclaration(
            modifier == null
                ? Visibility.PRIVATE
                : modifier.type() == TokenType.PROTECTED
                    ? Visibility.PROTECTED
                    : Visibility.PUBLIC,
            false,
            method,
            modifier == null
                ? method.range()
                : modifier.range().union(method.range())
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
            members.add(parseInterfaceMember());
        }
        final Token end = expect(TokenType.RIGHT_BRACE);
        return new InterfaceDeclaration(
            new IdentifierDeclaration(name.text(), name.range()),
            parents,
            members,
            keyword.range().union(end.range())
        );
    }

    private InterfaceMemberDeclaration parseInterfaceMember() {
        final Token finalModifier = functionModifier(TokenType.FINAL);
        if (finalModifier != null) {
            throw new ParserException(
                finalModifier.range().union(functionKeyword().range()),
                "'final' is not allowed on interface methods"
            );
        }
        if (check(TokenType.FUNC)) {
            return parseInterfaceMethod();
        }
        if (check(TokenType.CONST) || check(TokenType.VAR)) {
            return parseInterfaceVariable();
        }
        throw ParserException.expected("interface member", current());
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
                keyword.range().union(current().range()),
                "'%s' is not allowed on interface methods",
                keyword.text()
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

    private boolean functionFollows() {
        int offset = 0;
        while (
            check(offset, TokenType.CONST) || check(offset, TokenType.FINAL)
                || check(offset, TokenType.OVERRIDE)
                || check(offset, TokenType.ASYNC)
        ) {
            offset++;
        }
        return check(offset, TokenType.FUNC);
    }

    private boolean functionDeclarationStartsHere() {
        return check(TokenType.FUNC) || functionFollows();
    }

    private @Nullable Token functionModifier(final TokenType type) {
        int offset = 0;
        Token result = null;
        while (
            check(offset, TokenType.CONST) || check(offset, TokenType.FINAL)
                || check(offset, TokenType.OVERRIDE)
                || check(offset, TokenType.ASYNC)
        ) {
            if (check(offset, type)) {
                result = peek(offset);
            }
            offset++;
        }
        return check(offset, TokenType.FUNC) ? result : null;
    }

    private Token functionKeyword() {
        int offset = 0;
        while (
            check(offset, TokenType.CONST) || check(offset, TokenType.FINAL)
                || check(offset, TokenType.OVERRIDE)
                || check(offset, TokenType.ASYNC)
        ) {
            offset++;
        }
        return peek(offset);
    }

    private FunctionDeclaration parseModifiedFunctionDeclaration(
        final Token first
    ) {
        if (first.type() == TokenType.FUNC) {
            return parseFunctionDeclaration(first, false, List.of());
        }
        final List<FunctionModifier> modifiers = new ArrayList<>();
        boolean async = false;
        Token modifier = first;
        while (modifier.type() != TokenType.FUNC) {
            if (modifier.type() == TokenType.ASYNC) {
                if (async) {
                    throw new ParserException(
                        modifier.range(),
                        "Duplicate async function modifier"
                    );
                }
                async = true;
            }
            else {
                final FunctionModifier value = switch (modifier.type()) {
                    case CONST -> FunctionModifier.CONST;
                    case FINAL -> FunctionModifier.FINAL;
                    case OVERRIDE -> FunctionModifier.OVERRIDE;
                    default -> throw new IllegalStateException(
                        "Unexpected function modifier: " + modifier.type()
                    );
                };
                if (modifiers.contains(value)) {
                    throw new ParserException(
                        modifier.range(),
                        "Duplicate function modifier: %s",
                        modifier.text()
                    );
                }
                modifiers.add(value);
            }
            modifier = advance();
        }
        return parseFunctionDeclaration(first, async, modifiers);
    }

    private FunctionDeclaration parseFunctionDeclaration(
        final Token keyword,
        final boolean async,
        final List<FunctionModifier> modifiers
    ) {
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
            async,
            List.copyOf(modifiers),
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
        if (
            !field
                && (check(TokenType.LEFT_PAREN) || check(TokenType.LEFT_BRACE))
        ) {
            final Pattern pattern = parsePattern(true);
            expect(TokenType.EQUAL);
            final Expression initializer = parserExpressions.parseExpression();
            final Token semicolon = expect(TokenType.SEMICOLON);
            return new DestructuringDeclaration(
                mutability,
                pattern,
                initializer,
                keyword.range().union(semicolon.range())
            );
        }
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

    private boolean destructuringAssignmentFollows() {
        if (!check(TokenType.LEFT_PAREN) && !check(TokenType.LEFT_BRACE)) {
            return false;
        }
        final TokenType open =
            check(TokenType.LEFT_PAREN)
                ? TokenType.LEFT_PAREN
                : TokenType.LEFT_BRACE;
        final TokenType close =
            open == TokenType.LEFT_PAREN
                ? TokenType.RIGHT_PAREN
                : TokenType.RIGHT_BRACE;
        int depth = 0;
        boolean comma = false;
        for (int offset = 0; !isAtEnd(offset); offset++) {
            final TokenType type = peek(offset).type();
            if (type == open) {
                depth++;
            }
            else if (type == close) {
                depth--;
                if (depth == 0) {
                    return check(offset + 1, TokenType.EQUAL)
                        && (open == TokenType.LEFT_BRACE || comma);
                }
            }
            else if (type == TokenType.COMMA && depth == 1) {
                comma = true;
            }
        }
        return false;
    }

    private DestructuringAssignmentStatement parseDestructuringAssignment() {
        final Pattern pattern = parsePattern(false);
        expect(TokenType.EQUAL);
        final Expression value = parserExpressions.parseExpression();
        final Token semicolon = expect(TokenType.SEMICOLON);
        return new DestructuringAssignmentStatement(
            pattern,
            value,
            pattern.range().union(semicolon.range())
        );
    }

    private Pattern parsePattern(final boolean declaration) {
        if (check(TokenType.LEFT_PAREN)) {
            return parseTuplePattern(declaration);
        }
        return parseRecordPattern(declaration);
    }

    private TuplePattern parseTuplePattern(final boolean declaration) {
        final Token open = expect(TokenType.LEFT_PAREN);
        final List<Pattern> elements = new ArrayList<>();
        do {
            elements.add(parseIdentifierPattern(declaration, true));
        } while (match(TokenType.COMMA));
        final Token close = expect(TokenType.RIGHT_PAREN);
        if (elements.size() < 2) {
            throw new ParserException(
                open.range().union(close.range()),
                "A tuple pattern requires at least 2 elements"
            );
        }
        return new TuplePattern(
            List.copyOf(elements),
            open.range().union(close.range())
        );
    }

    private RecordPattern parseRecordPattern(final boolean declaration) {
        final Token open = expect(TokenType.LEFT_BRACE);
        final List<RecordPatternField> fields = new ArrayList<>();
        if (!check(TokenType.RIGHT_BRACE)) {
            do {
                final Token component = expect(TokenType.IDENTIFIER);
                final IdentifierDeclaration componentName =
                    new IdentifierDeclaration(
                        component.text(),
                        component.range()
                    );
                final Pattern target;
                if (match(TokenType.AS)) {
                    target = parseIdentifierPattern(declaration, true);
                }
                else {
                    target =
                        parseIdentifierPattern(component, declaration, true);
                }
                fields.add(
                    new RecordPatternField(
                        componentName,
                        target,
                        component.range().union(target.range())
                    )
                );
            } while (match(TokenType.COMMA));
        }
        final Token close = expect(TokenType.RIGHT_BRACE);
        return new RecordPattern(
            List.copyOf(fields),
            open.range().union(close.range())
        );
    }

    private Pattern parseIdentifierPattern(
        final boolean declaration,
        final boolean allowType
    ) {
        final Token name = expect(TokenType.IDENTIFIER);
        return parseIdentifierPattern(name, declaration, allowType);
    }

    private Pattern parseIdentifierPattern(
        final Token name,
        final boolean declaration,
        final boolean allowType
    ) {
        if (name.text().equals("_")) {
            if (check(TokenType.COLON)) {
                throw new ParserException(
                    current().range(),
                    "A discard pattern cannot have a type"
                );
            }
            return new DiscardPattern(name.range());
        }
        final TypeNode type =
            declaration && allowType && match(TokenType.COLON)
                ? parseType()
                : null;
        if (!declaration && check(TokenType.COLON)) {
            throw new ParserException(
                current().range(),
                "Assignment patterns cannot declare types"
            );
        }
        final IdentifierDeclaration identifier =
            new IdentifierDeclaration(name.text(), name.range());
        return new IdentifierPattern(
            identifier,
            type,
            type == null ? name.range() : name.range().union(type.range())
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
        final Token constant = matchToken(TokenType.CONST);
        if (constant != null) {
            final TypeNode type = parseTypeMember();
            return new ConstTypeNode(
                type,
                constant.range().union(type.range())
            );
        }
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
                        || check(TokenType.LEFT_BRACKET)
                        || check(TokenType.CONST) ? parseType() : null;
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
