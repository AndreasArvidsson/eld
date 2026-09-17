package mylang.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import mylang.parser.ArrayExpression;
import mylang.parser.ArrayTypeNode;
import mylang.parser.AssignmentExpression;
import mylang.parser.AstNode;
import mylang.parser.BinaryExpression;
import mylang.parser.BlockItem;
import mylang.parser.BlockStatement;
import mylang.parser.BreakStatement;
import mylang.parser.CallExpression;
import mylang.parser.ClassDeclaration;
import mylang.parser.ContinueStatement;
import mylang.parser.Declaration;
import mylang.parser.DeclarationStatement;
import mylang.parser.DoWhileStatement;
import mylang.parser.ElseIfBranch;
import mylang.parser.Expression;
import mylang.parser.ExpressionStatement;
import mylang.parser.ForEachStatement;
import mylang.parser.ForStatement;
import mylang.parser.FunctionDeclaration;
import mylang.parser.GroupingExpression;
import mylang.parser.IdentifierDeclaration;
import mylang.parser.IdentifierExpression;
import mylang.parser.IfExpression;
import mylang.parser.IndexExpression;
import mylang.parser.LiteralExpression;
import mylang.parser.LiteralKind;
import mylang.parser.UnaryOperator;
import mylang.parser.Mutability;
import mylang.parser.NamedTypeNode;
import mylang.parser.Parameter;
import mylang.parser.PostfixExpression;
import mylang.parser.Program;
import mylang.parser.ReturnStatement;
import mylang.parser.Statement;
import mylang.parser.SwitchBranch;
import mylang.parser.SwitchBranchBlockBody;
import mylang.parser.SwitchBranchBody;
import mylang.parser.SwitchBranchExpressionBody;
import mylang.parser.SwitchElseBranch;
import mylang.parser.SwitchExpression;
import mylang.parser.TernaryExpression;
import mylang.parser.TypeNode;
import mylang.parser.UnaryExpression;
import mylang.parser.VariableDeclaration;
import mylang.parser.WhileStatement;
import mylang.parser.YieldStatement;

public final class SemanticAnalyzer {
    private final SemanticModel model = new SemanticModel();

    public SemanticModel analyze(final Program program) {
        final Scope builtinScope = new Scope(null);
        builtinScope.declare(BuiltinFunctionSymbol.PRINT);

        final Scope globalScope = new Scope(builtinScope);
        final SemanticContext context =
            new SemanticContext(globalScope, null, 0);

        analyzeProgram(program, context);

        return model;
    }

    private void analyzeProgram(
        final Program program,
        final SemanticContext context
    ) {
        for (final @NonNull BlockItem item : program.items()) {
            analyzeBlockItem(item, context);
        }
    }

    private void analyzeBlockStatement(
        final BlockStatement block,
        final SemanticContext context
    ) {
        final SemanticContext blockContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth(),
                context.yields()
            );
        for (final BlockItem item : block.items()) {
            analyzeBlockItem(item, blockContext);
        }
    }

    private void analyzeBlockItem(
        final BlockItem item,
        final SemanticContext context
    ) {
        switch (item) {
            case Declaration declaration ->
                analyzeDeclaration(declaration, context);
            case Statement statement -> analyzeStatement(statement, context);
        }
    }

    private void analyzeDeclaration(
        final Declaration declaration,
        final SemanticContext context
    ) {
        switch (declaration) {
            case VariableDeclaration variableDeclaration ->
                analyzeVariableDeclaration(variableDeclaration, context);
            case FunctionDeclaration functionDeclaration ->
                analyzeFunctionDeclaration(functionDeclaration, context);
            case ClassDeclaration classDeclaration ->
                analyzeClassDeclaration(classDeclaration, context);
            default -> throw new SemanticException(
                declaration.range(),
                "Unsupported declaration: %s",
                declaration
            );
        }
    }

    private void analyzeStatement(
        final Statement statement,
        final SemanticContext context
    ) {
        switch (statement) {
            case DeclarationStatement declarationStatement ->
                analyzeDeclaration(declarationStatement.declaration(), context);
            case ExpressionStatement expressionStatement -> {
                if (
                    expressionStatement
                        .expression() instanceof IfExpression ifExpression
                ) {
                    analyzeIfExpressionBranches(ifExpression, context);
                    model.setExpressionType(ifExpression, BuiltinType.VOID);
                }
                else {
                    analyzeDiscardedExpression(
                        expressionStatement.expression(),
                        context
                    );
                }
            }
            case WhileStatement whileStatement ->
                analyzeWhileStatement(whileStatement, context);
            case DoWhileStatement doWhileStatement ->
                analyzeDoWhileStatement(doWhileStatement, context);
            case ForStatement forStatement ->
                analyzeForStatement(forStatement, context);
            case ForEachStatement forStatement ->
                analyzeForEachStatement(forStatement, context);
            case ContinueStatement continueStatement ->
                analyzeContinueStatement(continueStatement, context);
            case BreakStatement breakStatement ->
                analyzeBreakStatement(breakStatement, context);
            case YieldStatement yieldStatement ->
                analyzeYieldStatement(yieldStatement, context);
            case ReturnStatement returnStatement ->
                analyzeReturnStatement(returnStatement, context);
            case BlockStatement blockStatement ->
                analyzeBlockStatement(blockStatement, context);
            default -> throw new SemanticException(
                statement.range(),
                "Unsupported statement: %s",
                statement
            );
        }
    }

    private void analyzeClassDeclaration(
        final ClassDeclaration declaration,
        final SemanticContext context
    ) {
        final SemanticContext classContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                0
            );

        for (final var member : declaration.members()) {
            analyzeBlockItem(member, classContext);
        }

        final ClassType classType = new ClassType(declaration.name().name());
        final ClassSymbol classSymbol =
            new ClassSymbol(declaration.name(), classType);
        model.setSymbol(declaration.name(), classSymbol);
        classContext.scope().declare(classSymbol);
    }

    private void analyzeForStatement(
        final ForStatement statement,
        final SemanticContext context
    ) {
        final SemanticContext loopContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth() + 1,
                context.yields()
            );

        final Statement initializer = statement.initializer();
        final Expression condition = statement.condition();
        final Expression update = statement.update();

        if (initializer != null) {
            analyzeStatement(initializer, loopContext);
        }

        if (condition != null) {
            final Type conditionType =
                analyzeExpression(condition, loopContext);
            if (conditionType != BuiltinType.BOOL) {
                throw new SemanticException(
                    condition.range(),
                    "For condition must be bool, found %s",
                    conditionType
                );
            }
        }

        if (update != null) {
            analyzeDiscardedExpression(update, loopContext);
        }

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeForEachStatement(
        final ForEachStatement statement,
        final SemanticContext context
    ) {
        final SemanticContext loopContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth() + 1,
                context.yields()
            );
        final Type iterableType =
            analyzeExpression(statement.iterable(), loopContext);
        if (!(iterableType instanceof ArrayType arrayType)) {
            throw new SemanticException(
                statement.iterable().range(),
                "For-each iterable must be an array, found %s",
                iterableType
            );
        }
        final IdentifierDeclaration index = statement.index();

        final VariableSymbol valueSymbol =
            new VariableSymbol(
                statement.value(),
                arrayType.elementType(),
                Mutability.CONST
            );

        model.setSymbol(statement.value(), valueSymbol);
        loopContext.scope().declare(valueSymbol);

        if (index != null) {
            final VariableSymbol indexSymbol =
                new VariableSymbol(index, BuiltinType.I32, Mutability.CONST);
            model.setSymbol(index, indexSymbol);
            loopContext.scope().declare(indexSymbol);
        }

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeWhileStatement(
        final WhileStatement statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzeExpression(statement.condition(), context);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                statement.condition().range(),
                "While condition must be bool, found %s",
                conditionType
            );
        }

        final SemanticContext loopContext =
            new SemanticContext(
                context.scope(),
                context.function(),
                context.loopDepth() + 1,
                context.yields()
            );

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeDoWhileStatement(
        final DoWhileStatement statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzeExpression(statement.condition(), context);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                statement.condition().range(),
                "Do-while condition must be bool, found %s",
                conditionType
            );
        }

        final SemanticContext loopContext =
            new SemanticContext(
                context.scope(),
                context.function(),
                context.loopDepth() + 1,
                context.yields()
            );

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeIfExpressionBranches(
        final IfExpression statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzeExpression(statement.condition(), context);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                statement.condition().range(),
                "If condition must be bool, found %s",
                conditionType
            );
        }

        analyzeBlockStatement(statement.thenBranch(), context);

        for (final ElseIfBranch branch : statement.elifBranches()) {
            final Type branchConditionType =
                analyzeExpression(branch.condition(), context);

            if (branchConditionType != BuiltinType.BOOL) {
                throw new SemanticException(
                    branch.condition().range(),
                    "Else-if condition must be bool, found %s",
                    branchConditionType
                );
            }

            analyzeBlockStatement(branch.branch(), context);
        }

        final @Nullable Statement elseBranch = statement.elseBranch();

        if (elseBranch != null) {
            analyzeStatement(elseBranch, context);
        }
    }

    private void analyzeContinueStatement(
        final ContinueStatement statement,
        final SemanticContext context
    ) {
        if (!context.isWithinLoop()) {
            throw new SemanticException(
                statement.range(),
                "A 'continue' statement can only be used within an enclosing loop"
            );
        }
    }

    private void analyzeBreakStatement(
        final BreakStatement statement,
        final SemanticContext context
    ) {
        if (!context.isWithinLoop()) {
            throw new SemanticException(
                statement.range(),
                "A 'break' statement can only be used within an enclosing loop"
            );
        }
    }

    private void analyzeYieldStatement(
        final YieldStatement statement,
        final SemanticContext context
    ) {
        final List<YieldStatement> yields = context.yields();
        if (yields == null) {
            throw new SemanticException(
                statement.range(),
                "A 'yield' statement can only be used within an enclosing if or switch expression"
            );
        }
        if (analyzeExpression(statement.value(), context) == BuiltinType.VOID) {
            throw new SemanticException(
                statement.range(),
                "A yield statement must produce a value"
            );
        }
        yields.add(statement);
    }

    private void analyzeDiscardedExpression(
        final Expression expression,
        final SemanticContext context
    ) {
        if (expression instanceof SwitchExpression selection) {
            analyzeSwitchExpression(selection, context, false);
            model.setExpressionType(selection, BuiltinType.VOID);
        }
        else if (expression instanceof GroupingExpression grouping) {
            analyzeDiscardedExpression(grouping.expression(), context);
            model.setExpressionType(
                grouping,
                model.getExpressionType(grouping.expression())
            );
        }
        else {
            analyzeExpression(expression, context);
        }
    }

    private Type analyzeSwitchExpression(
        final SwitchExpression expression,
        final SemanticContext context,
        final boolean requireValue
    ) {
        final Type subjectType =
            analyzeExpression(expression.subject(), context);
        if (subjectType == BuiltinType.VOID) {
            throw new SemanticException(
                expression.subject().range(),
                "A switch subject must produce a value"
            );
        }
        if (requireValue && expression.elseBranch() == null) {
            throw new SemanticException(
                expression.range(),
                "A switch expression requires an else branch"
            );
        }
        final List<SwitchBranchBody> bodies = new ArrayList<>();
        for (final SwitchBranch branch : expression.branches()) {
            for (final Expression match : branch.matches()) {
                final Type matchType = analyzeExpression(match, context);
                if (!subjectType.equals(matchType)) {
                    throw new SemanticException(
                        match.range(),
                        "Switch match type %s does not match subject type %s",
                        matchType,
                        subjectType
                    );
                }
            }
            bodies.add(branch.body());
        }
        final SwitchElseBranch elseBranch = expression.elseBranch();
        if (elseBranch != null) {
            bodies.add(elseBranch.body());
        }
        Type result = BuiltinType.VOID;
        for (final SwitchBranchBody body : bodies) {
            final List<YieldStatement> yields = new ArrayList<>();
            final SemanticContext branchContext =
                new SemanticContext(
                    context.scope(),
                    context.function(),
                    requireValue ? 0 : context.loopDepth(),
                    yields
                );
            final List<Expression> values = new ArrayList<>();
            if (body instanceof SwitchBranchExpressionBody compact) {
                if (requireValue) {
                    analyzeExpression(compact.expression(), branchContext);
                    values.add(compact.expression());
                }
                else {
                    analyzeDiscardedExpression(
                        compact.expression(),
                        branchContext
                    );
                }
            }
            else if (body instanceof SwitchBranchBlockBody block) {
                analyzeStatement(block.block(), branchContext);
                if (requireValue && !producesValue(block.block())) {
                    throw new SemanticException(
                        body.range(),
                        "Every branch of a switch expression must yield a value"
                    );
                }
            }
            for (final YieldStatement statement : yields) {
                values.add(statement.value());
            }
            if (requireValue) {
                for (final Expression value : values) {
                    final Type type = model.getExpressionType(value);
                    if (type == BuiltinType.VOID) {
                        throw new SemanticException(
                            value.range(),
                            "Every branch of a switch expression must produce a value"
                        );
                    }
                    result =
                        result == BuiltinType.VOID
                            ? type
                            : commonBranchType(result, type, value);
                }
            }
        }
        if (requireValue && result == BuiltinType.VOID) {
            throw new SemanticException(
                expression.range(),
                "A switch expression must produce a value"
            );
        }
        return result;
    }

    private Type analyzeIfExpression(
        final IfExpression expression,
        final SemanticContext context
    ) {
        final List<YieldStatement> yields = new ArrayList<>();
        final SemanticContext branchContext =
            new SemanticContext(context.scope(), context.function(), 0, yields);
        final BlockStatement otherwise = expression.elseBranch();
        if (otherwise == null) {
            throw new SemanticException(
                expression.range(),
                "An if expression requires an else branch"
            );
        }
        analyzeIfExpressionBranches(expression, branchContext);
        if (
            !producesValue(expression.thenBranch()) || !producesValue(otherwise)
                || expression.elifBranches()
                    .stream()
                    .anyMatch(branch -> !producesValue(branch.branch()))
                || yields.isEmpty()
        ) {
            throw new SemanticException(
                expression.range(),
                "Every branch of an if expression must yield a value"
            );
        }
        Type result = model.getExpressionType(yields.getFirst().value());
        for (final YieldStatement statement : yields) {
            final Type type = model.getExpressionType(statement.value());
            result = commonBranchType(result, type, statement.value());
        }
        return result;
    }

    private Type analyzeTernaryExpression(
        final TernaryExpression expression,
        final SemanticContext context
    ) {
        final Type condition =
            analyzeExpression(expression.condition(), context);
        if (condition != BuiltinType.BOOL) {
            throw new SemanticException(
                expression.condition().range(),
                "Ternary condition must be bool, found %s",
                condition
            );
        }
        final Type thenType =
            analyzeExpression(expression.thenBranch(), context);
        final Type elseType =
            analyzeExpression(expression.elseBranch(), context);
        if (thenType == BuiltinType.VOID || elseType == BuiltinType.VOID) {
            throw new SemanticException(
                expression.range(),
                "Ternary branches must produce values"
            );
        }
        return commonBranchType(thenType, elseType, expression.elseBranch());
    }

    private Type commonBranchType(
        final Type left,
        final Type right,
        final AstNode node
    ) {
        if (left.equals(right)) {
            return left;
        }
        throw new SemanticException(
            node.range(),
            "Incompatible branch types: %s and %s",
            left,
            right
        );
    }

    private boolean producesValue(final BlockItem item) {
        return switch (item) {
            case YieldStatement ignored -> true;
            case ReturnStatement ignored -> true;
            case BlockStatement block -> {
                boolean produced = false;
                for (final BlockItem child : block.items()) {
                    if (
                        child instanceof BreakStatement
                            || child instanceof ContinueStatement
                    ) {
                        break;
                    }
                    if (producesValue(child)) {
                        produced = true;
                        break;
                    }
                }
                yield produced;
            }
            case ExpressionStatement statement when statement
                .expression() instanceof IfExpression conditional ->
                conditional.elseBranch() != null
                    && producesValue(conditional.thenBranch())
                    && producesValue(
                        Objects.requireNonNull(conditional.elseBranch())
                    )
                    && conditional.elifBranches()
                        .stream()
                        .allMatch(branch -> producesValue(branch.branch()));
            default -> false;
        };
    }

    private void analyzeReturnStatement(
        final ReturnStatement statement,
        final SemanticContext context
    ) {
        final FunctionSymbol function = context.function();

        if (function == null) {
            throw new SemanticException(
                statement.range(),
                "A 'return' statement can only be used within an enclosing function"
            );
        }

        final @Nullable Expression value = statement.value();
        final Type returnType = function.type().returnType();

        if (value == null) {
            if (returnType.equals(BuiltinType.VOID)) {
                return;
            }
            throw new SemanticException(
                statement.range(),
                "Return statement must return a value of type %s",
                returnType
            );
        }

        final Type valueType = analyzeExpression(value, context);
        final @Nullable Type resolvedType =
            resolveAssignType(valueType, returnType, value);

        if (resolvedType == null) {
            throw new SemanticException(
                statement.range(),
                "Type mismatch: cannot return %s from function with return type %s",
                valueType,
                returnType
            );
        }
    }

    private void analyzeVariableDeclaration(
        final VariableDeclaration declaration,
        final SemanticContext context
    ) {
        final TypeNode typeNode = declaration.type();
        final Expression initializer = declaration.initializer();
        final Type declaredType =
            typeNode != null ? resolveType(typeNode) : null;
        Type initializerType = analyzeExpression(initializer, context);

        if (initializerType == BuiltinType.VOID) {
            throw new SemanticException(
                declaration.range(),
                "A variable initializer must produce a value"
            );
        }

        if (declaredType != null) {
            final @Nullable Type resolvedInitializerType =
                resolveAssignType(initializerType, declaredType, initializer);
            if (resolvedInitializerType == null) {
                throw new SemanticException(
                    initializer.range(),
                    "Type mismatch: cannot assign %s to %s",
                    initializerType,
                    declaredType
                );
            }
            initializerType = resolvedInitializerType;
        }

        final VariableSymbol symbol =
            new VariableSymbol(
                declaration.name(),
                declaredType != null
                    ? declaredType
                    : Objects.requireNonNull(initializerType),
                declaration.mutability()
            );

        context.scope().declare(symbol);
        model.setSymbol(declaration.name(), symbol);
    }

    private void analyzeFunctionDeclaration(
        final FunctionDeclaration declaration,
        final SemanticContext context
    ) {
        // TODO: Verify that the parent is program or class body

        final List<@NonNull Type> parameterTypes = new ArrayList<>();
        final Scope functionScope = new Scope(context.scope());

        for (final Parameter param : declaration.parameters()) {
            final TypeNode typeNode = Objects.requireNonNull(param.type());
            final Type paramType = resolveType(typeNode);
            final VariableSymbol paramSymbol =
                new VariableSymbol(param.name(), paramType, Mutability.CONST);
            model.setResolvedType(typeNode, paramType);
            model.setSymbol(param.name(), paramSymbol);
            functionScope.declare(paramSymbol);
            parameterTypes.add(paramType);
        }

        final Type returnType =
            declaration.returnType() != null
                ? resolveType(Objects.requireNonNull(declaration.returnType()))
                : BuiltinType.VOID;

        final FunctionSymbol symbol =
            new FunctionSymbol(
                declaration.name(),
                new FunctionType(
                    Objects.requireNonNull(parameterTypes),
                    returnType
                )
            );

        context.scope().declare(symbol);
        model.setSymbol(declaration.name(), symbol);

        final SemanticContext functionContext =
            new SemanticContext(functionScope, symbol, 0);

        analyzeBlockStatement(declaration.body(), functionContext);
    }

    private Type resolveType(final TypeNode typeNode) {
        return switch (typeNode) {
            case NamedTypeNode named -> resolveNamedType(named);
            case ArrayTypeNode array -> {
                final Type type =
                    new ArrayType(resolveType(array.elementType()));
                model.setResolvedType(array, type);
                yield type;
            }
            default -> throw new SemanticException(
                typeNode.range(),
                "Unsupported type: %s",
                typeNode
            );
        };
    }

    private @Nullable Type resolveAssignType(
        final Type from,
        final Type to,
        final Expression fromExpression
    ) {

        // TODO: Extend this to handle more complex type assignability rules, such as
        // subtyping and type coercion.

        if (from.equals(to)) {
            return from;
        }

        if (
            fromExpression instanceof ArrayExpression array
                && to instanceof ArrayType target
        ) {
            for (final Expression element : array.elements()) {
                if (
                    resolveAssignType(
                        model.getExpressionType(element),
                        target.elementType(),
                        element
                    ) == null
                ) {
                    return null;
                }
            }
            model.setExpressionType(array, to);
            return to;
        }
        if (
            from instanceof BuiltinType source
                && to instanceof BuiltinType target
                && (source.isInteger() || source.isFloating())
                && (target.isInteger() || target.isFloating())
        ) {
            if (target.isFloating() && isFloatingLiteral(fromExpression)) {
                setLiteralType(fromExpression, target);
                return target;
            }
            final BigInteger literal = integerLiteral(fromExpression);
            if (literal != null && target.isInteger()) {
                if (literal.bitLength() >= target.bits()) {
                    return null;
                }
                model.setConversionType(fromExpression, to);
                return to;
            }
            if (
                !((source.isInteger()
                    && (target.isFloating() || target.bits() >= source.bits()))
                    || (source.isFloating() && target.isFloating()
                        && target.bits() >= source.bits()))
            ) {
                return null;
            }
            model.setConversionType(fromExpression, to);
            return to;
        }

        return null;
    }

    private Type resolveNamedType(final NamedTypeNode named) {
        final Type type = switch (named.name()) {
            case "i8" -> BuiltinType.I8;
            case "i16" -> BuiltinType.I16;
            case "i32" -> BuiltinType.I32;
            case "i64" -> BuiltinType.I64;
            case "f32" -> BuiltinType.F32;
            case "f64" -> BuiltinType.F64;
            case "char" -> BuiltinType.CHAR;
            case "boolean" -> BuiltinType.BOOL;
            case "string" -> BuiltinType.STRING;
            case "null" -> BuiltinType.NULL;
            default -> throw new SemanticException(
                named.range(),
                "Unknown type: %s",
                named.name()
            );
        };

        model.setResolvedType(named, type);

        return type;
    }

    private Type analyzeExpression(
        final Expression expression,
        final SemanticContext context
    ) {

        final Type type = switch (expression) {
            case LiteralExpression literal -> analyzeLiteralExpression(literal);
            case IdentifierExpression identifier ->
                analyzeIdentifierExpression(identifier, context);
            case ArrayExpression array ->
                analyzeArrayExpression(array, context);
            case BinaryExpression binary ->
                analyzeBinaryExpression(binary, context);
            case UnaryExpression unary ->
                analyzeUnaryExpression(unary, context);
            case PostfixExpression postfix ->
                analyzePostfixExpression(postfix, context);
            case CallExpression call -> analyzeCallExpression(call, context);
            case GroupingExpression grouping ->
                analyzeExpression(grouping.expression(), context);
            case IndexExpression index ->
                analyzeIndexExpression(index, context);
            case AssignmentExpression assignment ->
                analyzeAssignmentExpression(assignment, context);
            case TernaryExpression ternary ->
                analyzeTernaryExpression(ternary, context);
            case IfExpression conditional ->
                analyzeIfExpression(conditional, context);
            case SwitchExpression selection ->
                analyzeSwitchExpression(selection, context, true);
            // TODO: Implement lambda expression analysis
            // case LambdaExpression lambda ->
            // analyzeLambdaExpression(lambda, context);
            default -> throw new SemanticException(
                expression.range(),
                "Unsupported expression: %s",
                expression
            );
        };

        model.setExpressionType(expression, type);

        return type;
    }

    private Type analyzeCallExpression(
        final CallExpression call,
        final SemanticContext context
    ) {
        final Type type = analyzeExpression(call.callee(), context);
        if (type == BuiltinFunctionType.PRINT) {
            if (call.arguments().size() > 1) {
                throw new SemanticException(
                    call.range(),
                    "Print expects zero or one argument, found %s",
                    call.arguments().size()
                );
            }
            for (final Expression argument : call.arguments()) {
                if (analyzeExpression(argument, context) == BuiltinType.VOID) {
                    throw new SemanticException(
                        argument.range(),
                        "A print argument must produce a value"
                    );
                }
            }
            return BuiltinType.VOID;
        }
        if (!(type instanceof FunctionType function)) {
            throw new SemanticException(
                call.callee().range(),
                "Expression is not callable: %s",
                type
            );
        }
        if (call.arguments().size() != function.parameterTypes().size()) {
            throw new SemanticException(
                call.range(),
                "Expected %s arguments, found %s",
                function.parameterTypes().size(),
                call.arguments().size()
            );
        }
        for (int i = 0; i < call.arguments().size(); i++) {
            final Expression argument = call.arguments().get(i);
            final Type actual = analyzeExpression(argument, context);
            final Type expected = function.parameterTypes().get(i);
            if (resolveAssignType(actual, expected, argument) == null) {
                throw new SemanticException(
                    argument.range(),
                    "Cannot pass %s as %s",
                    actual,
                    expected
                );
            }
        }
        return function.returnType();
    }

    private Type analyzeIndexExpression(
        final IndexExpression index,
        final SemanticContext context
    ) {
        final Type target = analyzeExpression(index.target(), context);
        final Type subscript = analyzeExpression(index.index(), context);
        if (
            !(target instanceof ArrayType array)
                || !(subscript instanceof BuiltinType builtin
                    && builtin.isInteger()
                    && builtin != BuiltinType.I64)
        ) {
            throw new SemanticException(
                index.range(),
                "Indexing requires an array and an i8, i16, or i32 index"
            );
        }
        return array.elementType();
    }

    private Type analyzeAssignmentExpression(
        final AssignmentExpression assignment,
        final SemanticContext context
    ) {
        final Type target = analyzeExpression(assignment.target(), context);
        requireWritable(assignment.target());
        final Type value = analyzeExpression(assignment.value(), context);
        if (resolveAssignType(value, target, assignment.value()) == null) {
            throw new SemanticException(
                assignment.range(),
                "Cannot assign %s to %s",
                value,
                target
            );
        }
        return target;
    }

    private void requireWritable(final Expression expression) {
        if (expression instanceof GroupingExpression grouping) {
            requireWritable(grouping.expression());
            return;
        }
        if (expression instanceof IndexExpression) {
            return;
        }
        if (
            expression instanceof IdentifierExpression identifier
                && model
                    .getReference(identifier) instanceof VariableSymbol variable
                && variable.mutability() == Mutability.VAR
        ) {
            return;
        }
        throw new SemanticException(
            expression.range(),
            "Expression is not writable"
        );
    }

    private Type analyzePostfixExpression(
        final PostfixExpression postfix,
        final SemanticContext context
    ) {
        final Type type = analyzeExpression(postfix.operand(), context);
        requireWritable(postfix.operand());

        if (!numeric(type)) {
            throw new SemanticException(
                postfix.range(),
                "Increment requires a numeric operand"
            );
        }

        model.setExpressionType(postfix, type);
        return type;
    }

    private Type analyzeBinaryExpression(
        final BinaryExpression binary,
        final SemanticContext context
    ) {
        final Type leftType = analyzeExpression(binary.left(), context);
        final Type rightType = analyzeExpression(binary.right(), context);
        Type resolvedType = leftType;
        final boolean compatibleNumbers =
            numeric(leftType) && numeric(rightType);
        final boolean valid = switch (binary.operator()) {
            case AND, OR ->
                leftType == BuiltinType.BOOL && rightType == BuiltinType.BOOL;
            case EQUAL, NOT_EQUAL -> compatibleNumbers
                || (leftType.equals(rightType) && leftType != BuiltinType.VOID);
            case ADD -> compatibleNumbers || (leftType == BuiltinType.STRING
                && rightType == BuiltinType.STRING);
            default -> compatibleNumbers;
        };
        if (!valid) {
            throw new SemanticException(
                binary.range(),
                "Operator %s is not applicable to %s and %s",
                binary.operator(),
                leftType,
                rightType
            );
        }

        // TODO: Extend this to handle more complex type assignability rules, such as
        // subtyping and type coercion.

        if (compatibleNumbers) {
            resolvedType = promotedNumericType(leftType, rightType);
            if (!leftType.equals(resolvedType)) {
                model.setConversionType(binary.left(), resolvedType);
            }
            if (!rightType.equals(resolvedType)) {
                model.setConversionType(binary.right(), resolvedType);
            }
        }

        final Type resultType =
            binary.operator().isBool() ? BuiltinType.BOOL : resolvedType;
        model.setExpressionType(binary, resultType);
        return resultType;
    }

    private Type analyzeUnaryExpression(
        final UnaryExpression unary,
        final SemanticContext context
    ) {
        final BigInteger signedLiteral = integerLiteral(unary);
        if (signedLiteral != null) {
            final Type type = integerType(signedLiteral, unary);
            setLiteralType(unary.operand(), type);
            return type;
        }
        final Type operandType = analyzeExpression(unary.operand(), context);
        switch (unary.operator()) {
            case INCREMENT, DECREMENT -> {
                requireWritable(unary.operand());
                if (!numeric(operandType)) {
                    throw new SemanticException(
                        unary.range(),
                        "Increment requires a numeric operand"
                    );
                }
            }
            case NOT -> {
                if (operandType != BuiltinType.BOOL) {
                    throw new SemanticException(
                        unary.range(),
                        "Logical negation requires bool"
                    );
                }
            }
            case PLUS, MINUS -> {
                if (!numeric(operandType)) {
                    throw new SemanticException(
                        unary.range(),
                        "Unary arithmetic requires a numeric operand"
                    );
                }
            }
        }

        final Type resultType =
            unary.operator() == UnaryOperator.PLUS
                || unary.operator() == UnaryOperator.MINUS
                    ? promotedNumericType(operandType, BuiltinType.I32)
                    : operandType;
        if (!operandType.equals(resultType)) {
            model.setConversionType(unary.operand(), resultType);
        }
        model.setExpressionType(unary, resultType);
        return resultType;
    }

    private Type analyzeIdentifierExpression(
        final IdentifierExpression identifier,
        final SemanticContext context
    ) {
        final @Nullable Symbol symbol =
            context.scope().resolve(identifier.name());

        if (symbol == null) {
            throw new SemanticException(
                identifier.range(),
                "Undefined identifier: '%s'",
                identifier.name()
            );
        }

        final Type type = symbol.type();
        model.setExpressionType(identifier, type);
        model.setReference(identifier, symbol);
        return type;
    }

    // Java binary numeric promotion: double, float, long, otherwise int.
    private static BuiltinType promotedNumericType(
        final Type left,
        final Type right
    ) {
        if (left == BuiltinType.F64 || right == BuiltinType.F64) {
            return BuiltinType.F64;
        }
        if (left == BuiltinType.F32 || right == BuiltinType.F32) {
            return BuiltinType.F32;
        }
        if (left == BuiltinType.I64 || right == BuiltinType.I64) {
            return BuiltinType.I64;
        }
        return BuiltinType.I32;
    }

    private static boolean numeric(final Type type) {
        return type instanceof BuiltinType builtin
            && (builtin.isInteger() || builtin.isFloating()
                || builtin == BuiltinType.CHAR);
    }

    private static boolean isFloatingLiteral(final Expression expression) {
        if (expression instanceof LiteralExpression literal) {
            return literal.kind() == LiteralKind.FLOAT;
        }
        if (expression instanceof GroupingExpression grouping) {
            return isFloatingLiteral(grouping.expression());
        }
        return expression instanceof UnaryExpression unary
            && (unary.operator() == UnaryOperator.PLUS
                || unary.operator() == UnaryOperator.MINUS)
            && isFloatingLiteral(unary.operand());
    }

    public static @Nullable BigInteger integerLiteral(
        final Expression expression
    ) {
        if (
            expression instanceof LiteralExpression literal
                && literal.kind() == LiteralKind.INT
        ) {
            return new BigInteger(literal.text().replace("_", ""));
        }
        if (expression instanceof GroupingExpression grouping) {
            return integerLiteral(grouping.expression());
        }
        if (
            expression instanceof UnaryExpression unary
                && (unary.operator() == UnaryOperator.MINUS
                    || unary.operator() == UnaryOperator.PLUS)
        ) {
            final BigInteger value = integerLiteral(unary.operand());
            return value == null
                ? null
                : unary.operator() == UnaryOperator.MINUS
                    ? value.negate()
                    : value;
        }
        return null;
    }

    private static Type integerType(
        final BigInteger value,
        final Expression expression
    ) {
        if (value.bitLength() < 32) {
            return BuiltinType.I32;
        }
        if (value.bitLength() < 64) {
            return BuiltinType.I64;
        }
        throw new SemanticException(
            expression.range(),
            "Integer literal is outside the i64 range"
        );
    }

    private void setLiteralType(final Expression expression, final Type type) {
        model.setExpressionType(expression, type);
        if (expression instanceof GroupingExpression grouping) {
            setLiteralType(grouping.expression(), type);
        }
        if (expression instanceof UnaryExpression unary) {
            setLiteralType(unary.operand(), type);
        }
    }

    private Type analyzeLiteralExpression(final LiteralExpression literal) {
        final Type type = switch (literal.kind()) {
            case INT -> integerType(
                new BigInteger(literal.text().replace("_", "")),
                literal
            );
            case FLOAT -> BuiltinType.F64;
            case CHAR -> BuiltinType.CHAR;
            case BOOL -> BuiltinType.BOOL;
            case STRING -> BuiltinType.STRING;
            case NULL -> BuiltinType.NULL;
        };

        model.setExpressionType(literal, type);

        return type;
    }

    private ArrayType analyzeArrayExpression(
        final ArrayExpression array,
        final SemanticContext context
    ) {
        final List<@NonNull Type> elementTypes = new ArrayList<>();

        for (final Expression element : array.elements()) {
            elementTypes.add(analyzeExpression(element, context));
        }

        // For simplicity, we assume all elements must have the same type
        final Type elementType =
            elementTypes.isEmpty()
                ? BuiltinType.NULL
                : Objects.requireNonNull(elementTypes.get(0));

        if (elementType == BuiltinType.VOID) {
            throw new SemanticException(
                array.range(),
                "Array elements must produce values"
            );
        }

        for (final Type type : elementTypes) {
            if (!type.equals(elementType)) {
                throw new SemanticException(
                    array.range(),
                    "Array elements must have the same type"
                );
            }
        }

        final ArrayType type = new ArrayType(elementType);
        model.setExpressionType(array, type);
        return type;
    }
}
