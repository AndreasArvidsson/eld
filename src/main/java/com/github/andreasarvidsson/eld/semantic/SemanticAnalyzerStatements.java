package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.AstTraversal;
import com.github.andreasarvidsson.eld.parser.AwaitExpression;
import com.github.andreasarvidsson.eld.parser.BlockItem;
import com.github.andreasarvidsson.eld.parser.BlockStatement;
import com.github.andreasarvidsson.eld.parser.BreakStatement;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.ContinueStatement;
import com.github.andreasarvidsson.eld.parser.DoWhileStatement;
import com.github.andreasarvidsson.eld.parser.ElseIfBranch;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.ExpressionStatement;
import com.github.andreasarvidsson.eld.parser.ForEachStatement;
import com.github.andreasarvidsson.eld.parser.ForStatement;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.GroupingExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IfExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.Program;
import com.github.andreasarvidsson.eld.parser.ReturnStatement;
import com.github.andreasarvidsson.eld.parser.TryStatement;
import com.github.andreasarvidsson.eld.parser.ThrowStatement;
import com.github.andreasarvidsson.eld.parser.Statement;
import com.github.andreasarvidsson.eld.parser.SwitchBranch;
import com.github.andreasarvidsson.eld.parser.SwitchBranchBlockBody;
import com.github.andreasarvidsson.eld.parser.SwitchBranchBody;
import com.github.andreasarvidsson.eld.parser.SwitchBranchExpressionBody;
import com.github.andreasarvidsson.eld.parser.SwitchElseBranch;
import com.github.andreasarvidsson.eld.parser.SwitchExpression;
import com.github.andreasarvidsson.eld.parser.TernaryExpression;
import com.github.andreasarvidsson.eld.parser.TypeNode;
import com.github.andreasarvidsson.eld.parser.VariableDeclaration;
import com.github.andreasarvidsson.eld.parser.WhileStatement;
import com.github.andreasarvidsson.eld.parser.YieldStatement;

public final class SemanticAnalyzerStatements {
    private final SemanticAnalyzer analyzer;
    private final SemanticModel model;

    public SemanticAnalyzerStatements(
        final SemanticAnalyzer analyzer,
        final SemanticModel model
    ) {
        this.analyzer = analyzer;
        this.model = model;
    }

    public void analyzeForStatement(
        final ForStatement statement,
        final SemanticContext context
    ) {
        final SemanticContext loopContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth() + 1,
                context.yields(),
                context.yieldType()
            );

        final Statement initializer = statement.initializer();
        final Expression condition = statement.condition();
        final Expression update = statement.update();

        if (initializer != null) {
            analyzer.analyzeStatement(initializer, loopContext);
        }

        if (condition != null) {
            final Type conditionType =
                analyzer.analyzeExpression(condition, loopContext);
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

        analyzer.analyzeBlockStatement(statement.body(), loopContext);
    }

    public void analyzeForEachStatement(
        final ForEachStatement statement,
        final SemanticContext context
    ) {
        final SemanticContext loopContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth() + 1,
                context.yields(),
                context.yieldType()
            );
        final Type iterableType =
            analyzer.analyzeExpression(statement.iterable(), loopContext);
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

        analyzer.analyzeBlockStatement(statement.body(), loopContext);
    }

    public void analyzeWhileStatement(
        final WhileStatement statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzer.analyzeExpression(statement.condition(), context);

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
                context.yields(),
                context.yieldType()
            );

        analyzer.analyzeBlockStatement(statement.body(), loopContext);
    }

    public void analyzeDoWhileStatement(
        final DoWhileStatement statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzer.analyzeExpression(statement.condition(), context);

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
                context.yields(),
                context.yieldType()
            );

        analyzer.analyzeBlockStatement(statement.body(), loopContext);
    }

    public void analyzeIfExpressionBranches(
        final IfExpression statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzer.analyzeExpression(statement.condition(), context);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                statement.condition().range(),
                "If condition must be bool, found %s",
                conditionType
            );
        }

        analyzer.analyzeBlockStatement(statement.thenBranch(), context);

        for (final ElseIfBranch branch : statement.elifBranches()) {
            final Type branchConditionType =
                analyzer.analyzeExpression(branch.condition(), context);

            if (branchConditionType != BuiltinType.BOOL) {
                throw new SemanticException(
                    branch.condition().range(),
                    "Else-if condition must be bool, found %s",
                    branchConditionType
                );
            }

            analyzer.analyzeBlockStatement(branch.branch(), context);
        }

        final @Nullable Statement elseBranch = statement.elseBranch();

        if (elseBranch != null) {
            analyzer.analyzeStatement(elseBranch, context);
        }
    }

    public void analyzeContinueStatement(
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

    public void analyzeBreakStatement(
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

    public void analyzeYieldStatement(
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
        final Type expected = context.yieldType();
        final Type actual =
            analyzer.analyzeExpression(statement.value(), context, expected);
        if (actual == BuiltinType.VOID) {
            throw new SemanticException(
                statement.range(),
                "A yield statement must produce a value"
            );
        }
        if (
            expected != null && analyzer
                .resolveAssignType(actual, expected, statement.value()) == null
        ) {
            throw new SemanticException(
                statement.value().range(),
                "Cannot assign %s to %s",
                actual,
                expected
            );
        }
        yields.add(statement);
    }

    public void analyzeDiscardedExpression(
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
            final Type type = analyzer.analyzeExpression(expression, context);
            if (type instanceof PromiseType) {
                final String producer = promiseProducer(expression);
                if (producer == null) {
                    throw new SemanticException(
                        expression.range(),
                        "Promise is not awaited or otherwise used"
                    );
                }
                throw new SemanticException(
                    expression.range(),
                    "Promise returned by '%s' is not awaited or otherwise used",
                    producer
                );
            }
        }
    }

    private static @Nullable String promiseProducer(
        final Expression expression
    ) {
        if (!(expression instanceof CallExpression call)) {
            return null;
        }
        Expression callee = call.callee();
        while (callee instanceof GroupingExpression grouping) {
            callee = grouping.expression();
        }
        if (callee instanceof IdentifierExpression identifier) {
            return identifier.name();
        }
        if (callee instanceof MemberExpression member) {
            return member.member().name();
        }
        return null;
    }

    public Type analyzeSwitchExpression(
        final SwitchExpression expression,
        final SemanticContext context,
        final boolean requireValue
    ) {
        return analyzeSwitchExpression(expression, context, requireValue, null);
    }

    public Type analyzeSwitchExpression(
        final SwitchExpression expression,
        final SemanticContext context,
        final boolean requireValue,
        final @Nullable Type expected
    ) {
        final Type subjectType =
            analyzer.analyzeExpression(expression.subject(), context);
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
                final Type matchType =
                    analyzer.analyzeExpression(match, context);
                if (
                    !subjectType.equals(matchType)
                        && !((subjectType instanceof UnionType
                            || subjectType == BuiltinType.ANY)
                            && analyzer.resolveAssignType(
                                matchType,
                                subjectType,
                                match
                            ) != null)
                ) {
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
        final List<Expression> resultValues = new ArrayList<>();
        for (final SwitchBranchBody body : bodies) {
            final List<YieldStatement> yields = new ArrayList<>();
            final SemanticContext branchContext =
                new SemanticContext(
                    context.scope(),
                    context.function(),
                    requireValue ? 0 : context.loopDepth(),
                    yields,
                    expected
                );
            final List<Expression> values = new ArrayList<>();
            if (body instanceof SwitchBranchExpressionBody compact) {
                if (requireValue) {
                    final Type actual =
                        analyzer.analyzeExpression(
                            compact.expression(),
                            branchContext,
                            expected
                        );
                    if (
                        expected != null && analyzer.resolveAssignType(
                            actual,
                            expected,
                            compact.expression()
                        ) == null
                    ) {
                        throw new SemanticException(
                            compact.range(),
                            "Cannot assign %s to %s",
                            actual,
                            expected
                        );
                    }
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
                analyzer.analyzeStatement(block.block(), branchContext);
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
                resultValues.addAll(values);
                for (final Expression value : values) {
                    final Type type = model.getEffectiveType(value);
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
        if (requireValue) {
            applyCommonType(resultValues, result);
        }
        return result;
    }

    public Type analyzeIfExpression(
        final IfExpression expression,
        final SemanticContext context
    ) {
        return analyzeIfExpression(expression, context, null);
    }

    public Type analyzeIfExpression(
        final IfExpression expression,
        final SemanticContext context,
        final @Nullable Type expected
    ) {
        final List<YieldStatement> yields = new ArrayList<>();
        final SemanticContext branchContext =
            new SemanticContext(
                context.scope(),
                context.function(),
                0,
                yields,
                expected
            );
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
        Type result = model.getEffectiveType(yields.getFirst().value());
        for (final YieldStatement statement : yields) {
            final Type type = model.getEffectiveType(statement.value());
            result = commonBranchType(result, type, statement.value());
        }
        applyCommonType(
            yields.stream().map(YieldStatement::value).toList(),
            result
        );
        return result;
    }

    public Type analyzeTernaryExpression(
        final TernaryExpression expression,
        final SemanticContext context
    ) {
        final Type condition =
            analyzer.analyzeExpression(expression.condition(), context);
        if (condition != BuiltinType.BOOL) {
            throw new SemanticException(
                expression.condition().range(),
                "Ternary condition must be bool, found %s",
                condition
            );
        }
        final Type thenType =
            analyzer.analyzeExpression(expression.thenBranch(), context);
        final Type elseType =
            analyzer.analyzeExpression(expression.elseBranch(), context);
        if (thenType == BuiltinType.VOID || elseType == BuiltinType.VOID) {
            throw new SemanticException(
                expression.range(),
                "Ternary branches must produce values"
            );
        }
        final Type result =
            commonBranchType(thenType, elseType, expression.elseBranch());
        applyCommonType(
            List.of(expression.thenBranch(), expression.elseBranch()),
            result
        );
        return result;
    }

    public Type commonBranchType(
        final Type left,
        final Type right,
        final AstNode node
    ) {
        final Type common = analyzer.commonType(List.of(left, right));
        if (common != null) {
            return common;
        }
        throw new SemanticException(
            node.range(),
            "Incompatible branch types: %s and %s",
            left,
            right
        );
    }

    private void applyCommonType(
        final List<Expression> expressions,
        final Type type
    ) {
        for (final Expression expression : expressions) {
            final Type actual = model.getExpressionType(expression);
            if (analyzer.resolveAssignType(actual, type, expression) == null) {
                throw new SemanticException(
                    expression.range(),
                    "Cannot convert branch type %s to %s",
                    actual,
                    type
                );
            }
        }
    }

    private boolean producesValue(final BlockItem item) {
        return switch (item) {
            case YieldStatement _ -> true;
            case ReturnStatement _ -> true;
            case ThrowStatement _ -> true;
            case TryStatement statement -> (statement.finallyBody() != null
                && producesValue(statement.finallyBody()))
                || (producesValue(statement.body()) && statement.catches()
                    .stream()
                    .allMatch(clause -> producesValue(clause.body())));
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

    public void analyzeReturnStatement(
        final ReturnStatement statement,
        final SemanticContext context
    ) {
        final @Nullable FunctionSymbol function = context.function();
        if (function != null) {
            final List<ReturnStatement> inferredReturns =
                analyzer.lambdaReturns(function);
            if (inferredReturns != null) {
                if (statement.value() != null) {
                    analyzer.analyzeExpression(statement.value(), context);
                }
                inferredReturns.add(statement);
                return;
            }
        }
        if (analyzer.currentConstructor() != null && function == null) {
            if (statement.value() != null) {
                throw new SemanticException(
                    statement.range(),
                    "A constructor cannot return a value"
                );
            }
            return;
        }

        if (function == null) {
            throw new SemanticException(
                statement.range(),
                "A 'return' statement can only be used within an enclosing function"
            );
        }

        final @Nullable Expression value = statement.value();
        final Type asyncResult = model.getAsyncResultType(function);
        final Type returnType =
            asyncResult != null ? asyncResult : function.type().returnType();

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

        final Type valueType =
            analyzer.analyzeExpression(value, context, returnType);
        final @Nullable Type resolvedType =
            analyzer.resolveAssignType(valueType, returnType, value);

        if (resolvedType == null) {
            throw new SemanticException(
                statement.range(),
                "Type mismatch: cannot return %s from function with return type %s",
                valueType,
                returnType
            );
        }
    }

    public void analyzeVariableDeclaration(
        final VariableDeclaration declaration,
        final SemanticContext context
    ) {
        analyzeVariableDeclaration(declaration, context, context.scope());
    }

    public void analyzeVariableDeclaration(
        final VariableDeclaration declaration,
        final SemanticContext context,
        final Scope destination
    ) {
        final TypeNode typeNode = declaration.type();
        final Expression initializer = declaration.initializer();
        final Type declaredType =
            typeNode != null ? analyzer.resolveType(typeNode, context) : null;
        Type initializerType =
            analyzer.analyzeExpression(initializer, context, declaredType);

        if (initializerType == BuiltinType.VOID) {
            throw new SemanticException(
                declaration.range(),
                "A variable initializer must produce a value"
            );
        }

        if (declaredType != null) {
            final @Nullable Type resolvedInitializerType =
                analyzer.resolveAssignType(
                    initializerType,
                    declaredType,
                    initializer
                );
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

        destination.declare(symbol);
        model.setSymbol(declaration.name(), symbol);
    }

    public void analyzeFunctionDeclaration(
        final FunctionDeclaration declaration,
        final SemanticContext context,
        final AstNode parent
    ) {
        if (
            !(parent instanceof Program || parent instanceof ClassDeclaration)
        ) {
            throw new SemanticException(
                declaration.range(),
                "Functions are only allowed directly in a program or class body"
            );
        }
        registerFunction(declaration, context, context.scope());
        analyzeFunctionBody(declaration, context);
    }

    public void registerFunction(
        final FunctionDeclaration declaration,
        final SemanticContext context,
        final Scope destination
    ) {
        final List<@NonNull Type> parameterTypes = new ArrayList<>();
        final Scope functionScope = new Scope(context.scope());

        for (final FunctionParameter param : declaration.parameters()) {
            final TypeNode typeNode = param.type();
            final Type paramType = resolveParameterType(param, context);
            final VariableSymbol paramSymbol =
                new VariableSymbol(param.name(), paramType, Mutability.CONST);
            model.setResolvedType(typeNode, paramType);
            model.setSymbol(param.name(), paramSymbol);
            functionScope.declare(paramSymbol);
            parameterTypes.add(paramType);
        }

        final Type returnType =
            declaration.returnType() != null
                ? analyzer.resolveType(
                    Objects.requireNonNull(declaration.returnType()),
                    context
                )
                : BuiltinType.VOID;

        final Type callableReturnType =
            declaration.async() ? new PromiseType(returnType) : returnType;
        final FunctionSymbol symbol =
            new FunctionSymbol(
                declaration.name(),
                new FunctionType(
                    Objects.requireNonNull(parameterTypes),
                    callableReturnType
                )
            );

        destination.declare(symbol);
        model.setSymbol(declaration.name(), symbol);
        if (declaration.async()) {
            model.setAsyncResultType(symbol, returnType);
        }

        model.setFunctionParameters(
            symbol,
            declaration.parameters()
                .stream()
                .map(FunctionParameter::name)
                .toList()
        );

    }

    public void analyzeFunctionBody(
        final FunctionDeclaration declaration,
        final SemanticContext context
    ) {
        final FunctionSymbol symbol =
            (FunctionSymbol) model.getSymbol(declaration.name());
        final Scope functionScope = new Scope(context.scope());
        for (final FunctionParameter parameter : declaration.parameters()) {
            analyzeParameterDefault(
                parameter,
                new SemanticContext(functionScope, symbol, 0)
            );
            functionScope.declare(model.getSymbol(parameter.name()));
        }
        final SemanticContext functionContext =
            new SemanticContext(functionScope, symbol, 0);

        analyzer.analyzeBlockStatement(declaration.body(), functionContext);
    }

    public Type resolveParameterType(
        final FunctionParameter parameter,
        final SemanticContext context
    ) {
        if (parameter.optional() && parameter.defaultValue() != null) {
            throw new SemanticException(
                parameter.range(),
                "A parameter with a default value cannot also be optional; remove '?'"
            );
        }
        final Type declared = analyzer.resolveType(parameter.type(), context);
        final List<Type> members =
            new ArrayList<>(
                declared instanceof UnionType union
                    ? union.memberTypes()
                    : List.of(declared)
            );
        if (!members.contains(BuiltinType.NULL)) {
            members.add(BuiltinType.NULL);
        }
        final Type type =
            parameter.optional() ? UnionType.of(members) : declared;
        model.setParameterDetails(parameter);
        model.setResolvedType(parameter.type(), type);
        return type;
    }

    public void analyzeParameterDefault(
        final FunctionParameter parameter,
        final SemanticContext context
    ) {
        final Expression value = parameter.defaultValue();
        if (value == null) {
            return;
        }
        if (AstTraversal.anyMatch(value, AwaitExpression.class::isInstance)) {
            throw new SemanticException(
                value.range(),
                "Await is not allowed in a parameter default value"
            );
        }
        final Type expected = model.getSymbol(parameter.name()).type();
        final boolean previous = analyzer.isAnalyzingConstructorDefault();
        final Type actual;
        analyzer.setAnalyzingConstructorDefault(
            analyzer.currentConstructor() != null && context.function() == null
        );
        try {
            actual = analyzer.analyzeExpression(value, context, expected);
        }
        finally {
            analyzer.setAnalyzingConstructorDefault(previous);
        }
        if (analyzer.resolveAssignType(actual, expected, value) == null) {
            throw new SemanticException(
                value.range(),
                "Cannot use %s as default value for %s",
                actual,
                expected
            );
        }
    }

}
