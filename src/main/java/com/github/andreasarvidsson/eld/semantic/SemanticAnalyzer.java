package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.ArrayTypeNode;
import com.github.andreasarvidsson.eld.parser.AssignmentExpression;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.AstTraversal;
import com.github.andreasarvidsson.eld.parser.BinaryExpression;
import com.github.andreasarvidsson.eld.parser.BinaryOperator;
import com.github.andreasarvidsson.eld.parser.BlockItem;
import com.github.andreasarvidsson.eld.parser.BlockStatement;
import com.github.andreasarvidsson.eld.parser.BreakStatement;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.ContinueStatement;
import com.github.andreasarvidsson.eld.parser.Declaration;
import com.github.andreasarvidsson.eld.parser.DeclarationStatement;
import com.github.andreasarvidsson.eld.parser.DoWhileStatement;
import com.github.andreasarvidsson.eld.parser.ElseIfBranch;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.ExpressionStatement;
import com.github.andreasarvidsson.eld.parser.ForEachStatement;
import com.github.andreasarvidsson.eld.parser.ForStatement;
import com.github.andreasarvidsson.eld.parser.FormatStringExpression;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.FunctionTypeNode;
import com.github.andreasarvidsson.eld.parser.GroupingExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.IfExpression;
import com.github.andreasarvidsson.eld.parser.LambdaExpression;
import com.github.andreasarvidsson.eld.parser.LiteralExpression;
import com.github.andreasarvidsson.eld.parser.LiteralKind;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.NamedArgumentExpression;
import com.github.andreasarvidsson.eld.parser.NamedTypeNode;
import com.github.andreasarvidsson.eld.parser.NewExpression;
import com.github.andreasarvidsson.eld.parser.PostfixExpression;
import com.github.andreasarvidsson.eld.parser.Program;
import com.github.andreasarvidsson.eld.parser.ReturnStatement;
import com.github.andreasarvidsson.eld.parser.SliceExpression;
import com.github.andreasarvidsson.eld.parser.Statement;
import com.github.andreasarvidsson.eld.parser.SubscriptExpression;
import com.github.andreasarvidsson.eld.parser.SwitchBranch;
import com.github.andreasarvidsson.eld.parser.SwitchBranchBlockBody;
import com.github.andreasarvidsson.eld.parser.SwitchBranchBody;
import com.github.andreasarvidsson.eld.parser.SwitchBranchExpressionBody;
import com.github.andreasarvidsson.eld.parser.SwitchElseBranch;
import com.github.andreasarvidsson.eld.parser.SwitchExpression;
import com.github.andreasarvidsson.eld.parser.TernaryExpression;
import com.github.andreasarvidsson.eld.parser.ThisExpression;
import com.github.andreasarvidsson.eld.parser.TupleExpression;
import com.github.andreasarvidsson.eld.parser.TupleTypeNode;
import com.github.andreasarvidsson.eld.parser.TypeNode;
import com.github.andreasarvidsson.eld.parser.UnaryExpression;
import com.github.andreasarvidsson.eld.parser.UnaryOperator;
import com.github.andreasarvidsson.eld.parser.UninitializedVariableDeclaration;
import com.github.andreasarvidsson.eld.parser.UnionTypeNode;
import com.github.andreasarvidsson.eld.parser.VariableDeclaration;
import com.github.andreasarvidsson.eld.parser.WhileStatement;
import com.github.andreasarvidsson.eld.parser.YieldStatement;

public final class SemanticAnalyzer {
    private final SemanticModel model = new SemanticModel();
    private final Map<ClassType, Scope> classScopes = new HashMap<>();
    private @Nullable ClassType currentInstance;
    private @Nullable ConstructorDeclaration currentConstructor;
    private boolean analyzingConstructorDefault;
    private final IdentityHashMap<FunctionSymbol, List<ReturnStatement>> lambdaReturns =
        new IdentityHashMap<>();

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
                context.yields(),
                context.yieldType()
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
            case ConstructorDeclaration constructor ->
                throw new SemanticException(
                    constructor.range(),
                    "Constructors are only allowed directly in a class"
                );
            case UninitializedVariableDeclaration field ->
                throw new SemanticException(
                    field.range(),
                    "Uninitialized declarations are only allowed directly in a class"
                );
            case IdentifierDeclaration ignored -> throw new SemanticException(
                declaration.range(),
                "Unexpected declaration: %s",
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
        }
    }

    private void analyzeClassDeclaration(
        final ClassDeclaration declaration,
        final SemanticContext context
    ) {
        final ClassType classType = new ClassType(declaration.name().name());
        final ClassSymbol classSymbol =
            new ClassSymbol(declaration.name(), classType);
        model.setSymbol(declaration.name(), classSymbol);
        context.scope().declare(classSymbol);
        final Scope members = new Scope(null);
        classScopes.put(classType, members);
        ConstructorDeclaration constructor = null;
        for (final BlockItem member : declaration.members()) {
            if (member instanceof ConstructorDeclaration candidate) {
                if (constructor != null) {
                    throw new SemanticException(
                        candidate.range(),
                        "A class may only declare one constructor"
                    );
                }
                constructor = candidate;
            }
            else if (
                !(member instanceof VariableDeclaration)
                    && !(member instanceof UninitializedVariableDeclaration)
                    && !(member instanceof FunctionDeclaration)
            ) {
                throw new SemanticException(
                    member.range(),
                    "Class bodies may only contain fields, methods, and constructors"
                );
            }
        }
        final List<Type> parameterTypes = new ArrayList<>();
        if (constructor != null) {
            for (final FunctionParameter parameter : constructor.parameters()) {
                final Type type = resolveParameterType(parameter, context);
                parameterTypes.add(type);
                model.setSymbol(
                    parameter.name(),
                    new VariableSymbol(parameter.name(), type, Mutability.CONST)
                );
            }
        }
        final FunctionType constructorType =
            new FunctionType(parameterTypes, BuiltinType.VOID);
        model.setConstructor(classType, constructorType);
        model.setConstructorParameters(
            classType,
            constructor != null ? constructor.parameters() : List.of()
        );
        if (constructor != null) {
            model.setConstructorSymbol(
                constructor,
                new ConstructorSymbol(constructor, constructorType)
            );
        }
        for (final BlockItem member : declaration.members()) {
            if (member instanceof VariableDeclaration field) {
                analyzeVariableDeclaration(field, context, members);
            }
            else if (member instanceof UninitializedVariableDeclaration field) {
                final Type type = resolveType(field.type(), context);
                final VariableSymbol symbol =
                    new VariableSymbol(field.name(), type, field.mutability());
                members.declare(symbol);
                model.setSymbol(field.name(), symbol);
            }
            else if (member instanceof FunctionDeclaration method) {
                registerFunction(method, context, members);
            }
        }
        final ClassType previousInstance = currentInstance;
        final ConstructorDeclaration previousConstructor = currentConstructor;
        currentInstance = classType;
        try {
            for (final BlockItem member : declaration.members()) {
                if (member instanceof FunctionDeclaration method) {
                    analyzeFunctionBody(method, context);
                }
            }
            if (constructor != null) {
                currentConstructor = constructor;
                final Scope scope = new Scope(context.scope());
                for (final FunctionParameter parameter : constructor
                    .parameters()) {
                    analyzeParameterDefault(
                        parameter,
                        new SemanticContext(scope, null, 0)
                    );
                    scope.declare(model.getSymbol(parameter.name()));
                }
                analyzeBlockStatement(
                    constructor.body(),
                    new SemanticContext(scope, null, 0)
                );
            }
            new FieldInitializationAnalyzer(model, declaration)
                .analyze(constructor);
        }
        finally {
            currentInstance = previousInstance;
            currentConstructor = previousConstructor;
        }
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
                context.yields(),
                context.yieldType()
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
                context.yields(),
                context.yieldType()
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
                context.yields(),
                context.yieldType()
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
                context.yields(),
                context.yieldType()
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
        final Type expected = context.yieldType();
        final Type actual =
            analyzeExpression(statement.value(), context, expected);
        if (actual == BuiltinType.VOID) {
            throw new SemanticException(
                statement.range(),
                "A yield statement must produce a value"
            );
        }
        if (
            expected != null && resolveAssignType(
                actual,
                expected,
                statement.value()
            ) == null
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
        return analyzeSwitchExpression(expression, context, requireValue, null);
    }

    private Type analyzeSwitchExpression(
        final SwitchExpression expression,
        final SemanticContext context,
        final boolean requireValue,
        final @Nullable Type expected
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
                if (
                    !subjectType.equals(matchType)
                        && !((subjectType instanceof UnionType
                            || subjectType == BuiltinType.ANY)
                            && resolveAssignType(
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
                        analyzeExpression(
                            compact.expression(),
                            branchContext,
                            expected
                        );
                    if (
                        expected != null && resolveAssignType(
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
        return result;
    }

    private Type analyzeIfExpression(
        final IfExpression expression,
        final SemanticContext context
    ) {
        return analyzeIfExpression(expression, context, null);
    }

    private Type analyzeIfExpression(
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
        final List<ReturnStatement> inferredReturns =
            lambdaReturns.get(context.function());
        if (inferredReturns != null) {
            if (statement.value() != null) {
                analyzeExpression(statement.value(), context);
            }
            inferredReturns.add(statement);
            return;
        }
        if (currentConstructor != null && context.function() == null) {
            if (statement.value() != null) {
                throw new SemanticException(
                    statement.range(),
                    "A constructor cannot return a value"
                );
            }
            return;
        }
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

        final Type valueType = analyzeExpression(value, context, returnType);
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
        analyzeVariableDeclaration(declaration, context, context.scope());
    }

    private void analyzeVariableDeclaration(
        final VariableDeclaration declaration,
        final SemanticContext context,
        final Scope destination
    ) {
        final TypeNode typeNode = declaration.type();
        final Expression initializer = declaration.initializer();
        final Type declaredType =
            typeNode != null ? resolveType(typeNode, context) : null;
        Type initializerType =
            analyzeExpression(initializer, context, declaredType);

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

        destination.declare(symbol);
        model.setSymbol(declaration.name(), symbol);
    }

    private void analyzeFunctionDeclaration(
        final FunctionDeclaration declaration,
        final SemanticContext context
    ) {
        // TODO: Verify that the parent is program or class body
        registerFunction(declaration, context, context.scope());
        analyzeFunctionBody(declaration, context);
    }

    private void registerFunction(
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
                ? resolveType(
                    Objects.requireNonNull(declaration.returnType()),
                    context
                )
                : BuiltinType.VOID;

        final FunctionSymbol symbol =
            new FunctionSymbol(
                declaration.name(),
                new FunctionType(
                    Objects.requireNonNull(parameterTypes),
                    returnType
                )
            );

        destination.declare(symbol);
        model.setSymbol(declaration.name(), symbol);

        model.setFunctionParameters(
            symbol,
            declaration.parameters()
                .stream()
                .map(FunctionParameter::name)
                .toList()
        );

    }

    private void analyzeFunctionBody(
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

        analyzeBlockStatement(declaration.body(), functionContext);
    }

    private Type resolveParameterType(
        final FunctionParameter parameter,
        final SemanticContext context
    ) {
        if (parameter.optional() && parameter.defaultValue() != null) {
            throw new SemanticException(
                parameter.range(),
                "A parameter with a default value cannot also be optional; remove '?'"
            );
        }
        final Type declared = resolveType(parameter.type(), context);
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

    private void analyzeParameterDefault(
        final FunctionParameter parameter,
        final SemanticContext context
    ) {
        final Expression value = parameter.defaultValue();
        if (value == null) {
            return;
        }
        final Type expected = model.getSymbol(parameter.name()).type();
        final boolean previous = analyzingConstructorDefault;
        final Type actual;
        analyzingConstructorDefault =
            currentConstructor != null && context.function() == null;
        try {
            actual = analyzeExpression(value, context, expected);
        }
        finally {
            analyzingConstructorDefault = previous;
        }
        if (resolveAssignType(actual, expected, value) == null) {
            throw new SemanticException(
                value.range(),
                "Cannot use %s as default value for %s",
                actual,
                expected
            );
        }
    }

    private Type resolveType(
        final TypeNode typeNode,
        final SemanticContext context
    ) {
        return switch (typeNode) {
            case NamedTypeNode named -> resolveNamedType(named, context);
            case TupleTypeNode tuple -> {
                final Type type =
                    new TupleType(
                        tuple.elementTypes()
                            .stream()
                            .map(element -> resolveType(element, context))
                            .toList()
                    );
                model.setResolvedType(tuple, type);
                yield type;
            }
            case ArrayTypeNode array -> {
                final Type type =
                    new ArrayType(resolveType(array.elementType(), context));
                model.setResolvedType(array, type);
                yield type;
            }
            case FunctionTypeNode function -> {
                final TypeNode returnTypeNode = function.returnType();
                final Type returnType =
                    returnTypeNode == null
                        ? BuiltinType.VOID
                        : resolveType(returnTypeNode, context);
                final List<Type> parameterTypes = new ArrayList<>();
                for (final TypeNode paramTypeNode : function.parameterTypes()) {
                    parameterTypes.add(resolveType(paramTypeNode, context));
                }
                final Type type = new FunctionType(parameterTypes, returnType);
                model.setResolvedType(function, type);
                yield type;
            }
            case UnionTypeNode union -> {
                final List<Type> memberTypes = new ArrayList<>();
                for (final TypeNode memberTypeNode : union.memberTypes()) {
                    memberTypes.add(resolveType(memberTypeNode, context));
                }
                final Type type = UnionType.of(memberTypes);
                model.setResolvedType(union, type);
                yield type;
            }
        };
    }

    private @Nullable Type resolveAssignType(
        final Type from,
        final Type to,
        final Expression fromExpression
    ) {

        // TODO: Extend this to handle more complex type assignability rules, such as
        // subtyping and type coercion.

        if (
            fromExpression instanceof TupleExpression tuple
                && to instanceof TupleType target
        ) {
            if (tuple.elements().size() != target.elementTypes().size()) {
                return null;
            }
            for (int i = 0; i < tuple.elements().size(); i++) {
                final Expression element = tuple.elements().get(i);
                if (
                    resolveAssignType(
                        model.getExpressionType(element),
                        target.elementTypes().get(i),
                        element
                    ) == null
                ) {
                    return null;
                }
            }
            model.setExpressionType(tuple, to);
            return to;
        }
        if (from.equals(to)) {
            return from;
        }
        if (to == BuiltinType.ANY && from != BuiltinType.VOID) {
            model.setConversionType(fromExpression, to);
            return to;
        }

        if (to instanceof UnionType union) {
            if (union.contains(from)) {
                model.setUnionConversion(fromExpression, from, union);
                return to;
            }
            // Converting individual members of an existing union would require runtime dispatch.
            if (from instanceof UnionType) {
                return null;
            }
            // Exact members take priority above; numeric alternatives use a stable order.
            for (final BuiltinType member : BuiltinType.values()) {
                if (
                    from instanceof BuiltinType
                        && union.memberTypes().contains(member)
                        && resolveAssignType(
                            from,
                            member,
                            fromExpression
                        ) != null
                ) {
                    model.setUnionConversion(fromExpression, member, union);
                    return to;
                }
            }
            return null;
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
                model.setConversionType(fromExpression, target);
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

    private Type resolveNamedType(
        final NamedTypeNode named,
        final SemanticContext context
    ) {
        final Type type = switch (named.name()) {
            case "i8" -> BuiltinType.I8;
            case "i16" -> BuiltinType.I16;
            case "i32" -> BuiltinType.I32;
            case "i64" -> BuiltinType.I64;
            case "f32" -> BuiltinType.F32;
            case "f64" -> BuiltinType.F64;
            case "char" -> BuiltinType.CHAR;
            case "bool" -> BuiltinType.BOOL;
            case "string" -> BuiltinType.STRING;
            case "null" -> BuiltinType.NULL;
            case "any" -> BuiltinType.ANY;
            default -> {
                final Symbol symbol = context.scope().resolve(named.name());
                if (!(symbol instanceof ClassSymbol classSymbol)) {
                    throw new SemanticException(
                        named.range(),
                        "Unknown type: %s",
                        named.name()
                    );
                }
                yield classSymbol.type();
            }
        };

        model.setResolvedType(named, type);

        return type;
    }

    private Type analyzeExpression(
        final Expression expression,
        final SemanticContext context,
        final @Nullable Type expected
    ) {
        if (expression instanceof LambdaExpression lambda) {
            final Type type =
                analyzeLambdaExpression(lambda, context, expected);
            model.setExpressionType(lambda, type);
            return type;
        }
        if (expected == BuiltinType.ANY || expected instanceof UnionType) {
            if (expression instanceof IfExpression conditional) {
                final Type type =
                    analyzeIfExpression(conditional, context, expected);
                model.setExpressionType(expression, type);
                return type;
            }
            if (expression instanceof SwitchExpression selection) {
                final Type type =
                    analyzeSwitchExpression(selection, context, true, expected);
                model.setExpressionType(expression, type);
                return type;
            }
        }
        if (
            expected instanceof TupleType target
                && expression instanceof TupleExpression tuple
                && tuple.elements().size() == target.elementTypes().size()
        ) {
            return analyzeTupleExpression(tuple, context, target);
        }
        if (
            (expected instanceof UnionType || expected == BuiltinType.ANY)
                && expression instanceof TernaryExpression ternary
        ) {
            if (
                analyzeExpression(
                    ternary.condition(),
                    context
                ) != BuiltinType.BOOL
            ) {
                throw new SemanticException(
                    ternary.condition().range(),
                    "Ternary condition must be bool"
                );
            }
            for (final Expression branch : List
                .of(ternary.thenBranch(), ternary.elseBranch())) {
                final Type actual =
                    analyzeExpression(branch, context, expected);
                if (resolveAssignType(actual, expected, branch) == null) {
                    throw new SemanticException(
                        branch.range(),
                        "Cannot assign %s to %s",
                        actual,
                        expected
                    );
                }
            }
            model.setExpressionType(ternary, expected);
            return expected;
        }
        if (
            expected instanceof ArrayType target
                && expression instanceof ArrayExpression array
                && requiresContextualElements(target.elementType())
        ) {
            for (final Expression element : array.elements()) {
                final Type actual =
                    analyzeExpression(element, context, target.elementType());
                if (
                    resolveAssignType(
                        actual,
                        target.elementType(),
                        element
                    ) == null
                ) {
                    throw new SemanticException(
                        element.range(),
                        "Cannot assign %s to %s",
                        actual,
                        target.elementType()
                    );
                }
            }
            model.setExpressionType(array, target);
            return target;
        }
        if (
            expression instanceof GroupingExpression grouping
                && expected != null
        ) {
            final Type type =
                analyzeExpression(grouping.expression(), context, expected);
            model.setExpressionType(grouping, type);
            return type;
        }
        return analyzeExpression(expression, context);
    }

    private Type analyzeTupleExpression(
        final TupleExpression tuple,
        final SemanticContext context,
        final TupleType target
    ) {
        for (int i = 0; i < tuple.elements().size(); i++) {
            final Expression element = tuple.elements().get(i);
            final Type wanted = target.elementTypes().get(i);
            final Type actual = analyzeExpression(element, context, wanted);
            if (resolveAssignType(actual, wanted, element) == null) {
                throw new SemanticException(
                    element.range(),
                    "Cannot assign %s to %s",
                    actual,
                    wanted
                );
            }
        }
        model.setExpressionType(tuple, target);
        return target;
    }

    private static boolean requiresContextualElements(final Type type) {
        return (type instanceof TupleType tuple && tuple.elementTypes()
            .stream()
            .anyMatch(SemanticAnalyzer::requiresContextualElements))
            || type instanceof UnionType
            || type == BuiltinType.ANY
            || (type instanceof ArrayType array
                && requiresContextualElements(array.elementType()));
    }

    private Type analyzeExpression(
        final Expression expression,
        final SemanticContext context
    ) {

        final Type type = switch (expression) {
            case MemberExpression member -> {
                final Type target = analyzeExpression(member.target(), context);
                if (!(target instanceof ClassType classType)) {
                    throw new SemanticException(
                        member.target().range(),
                        "Member access requires a class instance"
                    );
                }
                final Scope scope = classScopes.get(classType);
                final Symbol symbol =
                    scope == null
                        ? null
                        : scope.resolveLocal(member.member().name());
                if (symbol == null) {
                    throw new SemanticException(
                        member.member().range(),
                        "Unknown member '%s' of class %s",
                        member.member().name(),
                        classType.name()
                    );
                }
                model.setMemberOwner(member, classType);
                model.setReference(member.member(), symbol);
                model.setExpressionType(member.member(), symbol.type());
                yield symbol.type();
            }
            case NewExpression creation -> {
                final Type classType =
                    analyzeIdentifierExpression(creation.className(), context);
                if (
                    !(model.getReference(
                        creation.className()
                    ) instanceof ClassSymbol)
                ) {
                    throw new SemanticException(
                        creation.className().range(),
                        "'new' requires a class name"
                    );
                }
                final FunctionType constructor =
                    model.getConstructor((ClassType) classType);
                if (
                    creation.arguments().size() > constructor.parameterTypes()
                        .size()
                ) {
                    throw new SemanticException(
                        creation.range(),
                        "Expected %s constructor arguments, found %s",
                        constructor.parameterTypes().size(),
                        creation.arguments().size()
                    );
                }
                for (int i = 0; i < creation.arguments().size(); i++) {
                    final Expression argument = creation.arguments().get(i);
                    final Type expectedType =
                        constructor.parameterTypes().get(i);
                    final Type actual =
                        analyzeExpression(argument, context, expectedType);
                    if (
                        resolveAssignType(
                            actual,
                            expectedType,
                            argument
                        ) == null
                    ) {
                        throw new SemanticException(
                            argument.range(),
                            "Cannot assign %s to %s",
                            actual,
                            expectedType
                        );
                    }
                }
                final List<FunctionParameter> parameters =
                    model.getConstructorParameters((ClassType) classType);
                for (int i = creation.arguments().size(); i < parameters
                    .size(); i++) {
                    if (!parameters.get(i).omittable()) {
                        throw new SemanticException(
                            creation.range(),
                            "Missing required constructor argument: %s",
                            parameters.get(i).name().name()
                        );
                    }
                }
                yield classType;
            }
            case ThisExpression self -> {
                if (analyzingConstructorDefault) {
                    throw new SemanticException(
                        self.range(),
                        "Constructor defaults cannot access 'this' before initialization"
                    );
                }
                if (currentInstance == null) {
                    throw new SemanticException(
                        self.range(),
                        "'this' is only available in instance methods and constructors"
                    );
                }
                yield currentInstance;
            }
            case FormatStringExpression format -> {
                for (final Expression part : format.parts()) {
                    if (analyzeExpression(part, context) == BuiltinType.VOID) {
                        throw new SemanticException(
                            part.range(),
                            "Cannot interpolate a void expression"
                        );
                    }
                }
                yield BuiltinType.STRING;
            }
            case LiteralExpression literal -> analyzeLiteralExpression(literal);
            case IdentifierExpression identifier ->
                analyzeIdentifierExpression(identifier, context);
            case TupleExpression tuple -> new TupleType(
                tuple.elements()
                    .stream()
                    .map(element -> analyzeExpression(element, context))
                    .toList()
            );
            case ArrayExpression array ->
                analyzeArrayExpression(array, context);
            case BinaryExpression binary ->
                analyzeBinaryExpression(binary, context);
            case UnaryExpression unary ->
                analyzeUnaryExpression(unary, context);
            case PostfixExpression postfix ->
                analyzePostfixExpression(postfix, context);
            case NamedArgumentExpression named -> throw new SemanticException(
                named.range(),
                "Named arguments are only valid in function calls"
            );
            case CallExpression call -> analyzeCallExpression(call, context);
            case GroupingExpression grouping ->
                analyzeExpression(grouping.expression(), context);
            case SubscriptExpression index ->
                analyzeIndexExpression(index, context);
            case SliceExpression slice ->
                analyzeSliceExpression(slice, context);
            case AssignmentExpression assignment ->
                analyzeAssignmentExpression(assignment, context);
            case TernaryExpression ternary ->
                analyzeTernaryExpression(ternary, context);
            case IfExpression conditional ->
                analyzeIfExpression(conditional, context);
            case SwitchExpression selection ->
                analyzeSwitchExpression(selection, context, true);
            case LambdaExpression lambda ->
                analyzeLambdaExpression(lambda, context, null);
        };

        model.setExpressionType(expression, type);

        return type;
    }

    private Type analyzeLambdaExpression(
        final LambdaExpression lambda,
        final SemanticContext context,
        final @Nullable Type expected
    ) {
        final FunctionType target =
            expected instanceof FunctionType function ? function : null;
        if (!lambda.parameters().isEmpty() && target == null) {
            throw new SemanticException(
                lambda.range(),
                "Lambda parameters require an expected function type"
            );
        }
        if (
            target != null
                && target.parameterTypes().size() != lambda.parameters().size()
        ) {
            throw new SemanticException(
                lambda.range(),
                "Lambda parameter count does not match expected function type"
            );
        }
        final Scope scope = new Scope(context.scope());
        final List<Type> parameterTypes =
            target != null ? target.parameterTypes() : List.of();
        for (int i = 0; i < lambda.parameters().size(); i++) {
            final var name = lambda.parameters().get(i).name();
            final VariableSymbol parameter =
                new VariableSymbol(
                    name,
                    parameterTypes.get(i),
                    Mutability.CONST
                );
            scope.declare(parameter);
            model.setSymbol(name, parameter);
        }
        final FunctionSymbol symbol =
            new FunctionSymbol(
                new IdentifierDeclaration("$lambda", lambda.range()),
                new FunctionType(
                    parameterTypes,
                    target != null ? target.returnType() : BuiltinType.ANY
                )
            );
        final SemanticContext lambdaContext =
            new SemanticContext(scope, symbol, 0);
        final Type returnType;
        if (lambda.body() instanceof Expression expression) {
            final Type actual =
                analyzeExpression(
                    expression,
                    lambdaContext,
                    target != null ? target.returnType() : null
                );
            returnType = target != null ? target.returnType() : actual;
            if (resolveAssignType(actual, returnType, expression) == null) {
                throw new SemanticException(
                    expression.range(),
                    "Cannot return %s from lambda returning %s",
                    actual,
                    returnType
                );
            }
        }
        else {
            final List<ReturnStatement> returns = new ArrayList<>();
            if (target == null) {
                lambdaReturns.put(symbol, returns);
            }
            try {
                analyzeBlockStatement(
                    (BlockStatement) lambda.body(),
                    lambdaContext
                );
            }
            finally {
                lambdaReturns.remove(symbol);
            }
            Type inferred = returns.isEmpty() ? BuiltinType.VOID : null;
            for (final ReturnStatement statement : returns) {
                final Type type =
                    statement.value() != null
                        ? model.getExpressionType(statement.value())
                        : BuiltinType.VOID;
                inferred =
                    inferred == null
                        ? type
                        : commonBranchType(inferred, type, statement);
            }
            returnType =
                target != null
                    ? target.returnType()
                    : Objects.requireNonNull(inferred);
            for (final ReturnStatement statement : returns) {
                if (statement.value() != null) {
                    resolveAssignType(
                        model.getExpressionType(statement.value()),
                        returnType,
                        statement.value()
                    );
                }
            }
        }
        final Set<Symbol> declared =
            Collections.newSetFromMap(new IdentityHashMap<>());
        AstTraversal.walk(lambda, node -> {
            final Symbol declaration = model.findDeclaredSymbol(node);
            if (declaration != null) {
                declared.add(declaration);
            }
        });
        final List<Symbol> captures = new ArrayList<>();
        AstTraversal.walk(lambda.body(), node -> {
            if (node instanceof IdentifierExpression identifier) {
                final Symbol reference = model.getReference(identifier);
                if (
                    reference instanceof VariableSymbol
                        && !declared.contains(reference)
                        && !captures.contains(reference)
                ) {
                    captures.add(reference);
                }
            }
        });
        model.setLambdaCaptures(lambda, captures);
        return new FunctionType(parameterTypes, returnType);
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
        if (call.arguments().size() > function.parameterTypes().size()) {
            throw new SemanticException(
                call.range(),
                "Expected %s arguments, found %s",
                function.parameterTypes().size(),
                call.arguments().size()
            );
        }
        Expression callee = call.callee();
        while (callee instanceof GroupingExpression grouping) {
            callee = grouping.expression();
        }
        final IdentifierExpression functionName =
            callee instanceof IdentifierExpression identifier
                ? identifier
                : callee instanceof MemberExpression member
                    ? member.member()
                    : null;
        final List<IdentifierDeclaration> declarations =
            functionName != null && model
                .getReference(functionName) instanceof FunctionSymbol symbol
                    ? model.getFunctionParameters(symbol)
                    : List.of();
        final List<String> names =
            declarations.stream().map(IdentifierDeclaration::name).toList();
        final List<Integer> parameters = new ArrayList<>();
        final boolean[] assigned =
            new boolean[function.parameterTypes().size()];
        boolean seenNamed = false;
        for (int i = 0; i < call.arguments().size(); i++) {
            final Expression supplied = call.arguments().get(i);
            final Expression argument;
            final int parameter;
            if (supplied instanceof NamedArgumentExpression named) {
                seenNamed = true;
                if (names.isEmpty()) {
                    throw new SemanticException(
                        named.range(),
                        "Named arguments require a declared function"
                    );
                }
                parameter = names.indexOf(named.name().name());
                if (parameter < 0) {
                    throw new SemanticException(
                        named.name().range(),
                        "Unknown parameter: %s",
                        named.name().name()
                    );
                }
                argument = named.value();
            }
            else {
                if (seenNamed) {
                    throw new SemanticException(
                        supplied.range(),
                        "Positional arguments must precede named arguments"
                    );
                }
                parameter = i;
                argument = supplied;
            }
            if (assigned[parameter]) {
                throw new SemanticException(
                    supplied.range(),
                    "Argument supplied more than once for parameter: %s",
                    names.get(parameter)
                );
            }
            assigned[parameter] = true;
            parameters.add(parameter);
            if (supplied instanceof NamedArgumentExpression named) {
                model.setNamedArgument(
                    named.name(),
                    declarations.get(parameter)
                );
            }
            final Type expected = function.parameterTypes().get(parameter);
            final Type actual = analyzeExpression(argument, context, expected);
            if (resolveAssignType(actual, expected, argument) == null) {
                throw new SemanticException(
                    argument.range(),
                    "Cannot pass %s as %s",
                    actual,
                    expected
                );
            }
        }
        for (int i = 0; i < assigned.length; i++) {
            if (
                !assigned[i] && (declarations.isEmpty()
                    || !model.getParameterDetails(declarations.get(i))
                        .omittable())
            ) {
                throw new SemanticException(
                    call.range(),
                    "Expected %s arguments, found %s",
                    function.parameterTypes().size(),
                    call.arguments().size()
                );
            }
        }
        model.setArgumentParameters(call, parameters);
        return function.returnType();
    }

    private Type analyzeIndexExpression(
        final SubscriptExpression subscript,
        final SemanticContext context
    ) {
        final Type target = analyzeExpression(subscript.target(), context);
        if (target instanceof TupleType tuple) {
            analyzeExpression(subscript.index(), context);
            final BigInteger index = integerLiteral(subscript.index());
            if (
                index == null || index.signum() < 0
                    || index.compareTo(
                        BigInteger.valueOf(tuple.elementTypes().size())
                    ) >= 0
            ) {
                throw new SemanticException(
                    subscript.index().range(),
                    "Tuple index must be an integer literal between 0 and %s",
                    tuple.elementTypes().size() - 1
                );
            }
            return tuple.elementTypes().get(index.intValue());
        }
        if (!(target instanceof ArrayType array)) {
            throw new SemanticException(
                subscript.range(),
                "Subscript requires an array target"
            );
        }
        final Type index = analyzeExpression(subscript.index(), context);
        if (!isValidSubscriptIndex(index)) {
            throw new SemanticException(
                subscript.range(),
                "Subscript requires an i8, i16, or i32 index"
            );
        }
        return array.elementType();
    }

    private Type analyzeSliceExpression(
        final SliceExpression slice,
        final SemanticContext context
    ) {
        final Type target = analyzeExpression(slice.target(), context);
        final Expression start = slice.startIndex();
        final Expression end = slice.endIndex();
        final Type startIndex =
            start == null ? null : analyzeExpression(start, context);
        final Type endIndex =
            end == null ? null : analyzeExpression(end, context);
        if (!(target instanceof ArrayType array)) {
            throw new SemanticException(
                slice.range(),
                "Slicing requires an array target"
            );
        }
        if (startIndex != null && !isValidSubscriptIndex(startIndex)) {
            throw new SemanticException(
                Objects.requireNonNull(start).range(),
                "Slice start index must be an i8, i16, or i32"
            );
        }
        if (endIndex != null && !isValidSubscriptIndex(endIndex)) {
            throw new SemanticException(
                Objects.requireNonNull(end).range(),
                "Slice end index must be an i8, i16, or i32"
            );
        }
        return array;
    }

    private boolean isValidSubscriptIndex(final Type index) {
        return index instanceof BuiltinType builtin && builtin.isInteger()
            && builtin != BuiltinType.I64;
    }

    private Type analyzeAssignmentExpression(
        final AssignmentExpression assignment,
        final SemanticContext context
    ) {
        final Type target = analyzeExpression(assignment.target(), context);
        if (
            !(currentConstructor != null && context.function() == null
                && unwrap(
                    assignment.target()
                ) instanceof MemberExpression member
                && unwrap(member.target()) instanceof ThisExpression
                && model
                    .getReference(member.member()) instanceof VariableSymbol)
        ) {
            requireWritable(assignment.target());
        }
        final Type value =
            analyzeExpression(assignment.value(), context, target);
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

    private static Expression unwrap(final Expression expression) {
        return expression instanceof GroupingExpression grouping
            ? unwrap(grouping.expression())
            : expression;
    }

    private void requireWritable(final Expression expression) {
        if (expression instanceof GroupingExpression grouping) {
            requireWritable(grouping.expression());
            return;
        }
        if (
            expression instanceof MemberExpression member
                && model.getReference(
                    member.member()
                ) instanceof VariableSymbol variable
                && variable.mutability() == Mutability.VAR
        ) {
            return;
        }
        if (expression instanceof SubscriptExpression subscript) {
            if (
                model.getExpressionType(subscript.target()) instanceof TupleType
            ) {
                throw new SemanticException(
                    expression.range(),
                    "Tuple elements are not writable"
                );
            }
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
        boolean unionEquality = false;
        if (
            binary.operator() == BinaryOperator.EQUAL
                || binary.operator() == BinaryOperator.NOT_EQUAL
        ) {
            if (leftType instanceof UnionType || leftType == BuiltinType.ANY) {
                unionEquality =
                    resolveAssignType(
                        rightType,
                        leftType,
                        binary.right()
                    ) != null;
            }
            if (
                !unionEquality && (rightType instanceof UnionType
                    || rightType == BuiltinType.ANY)
            ) {
                unionEquality =
                    resolveAssignType(
                        leftType,
                        rightType,
                        binary.left()
                    ) != null;
            }
        }
        Type resolvedType = leftType;
        final boolean compatibleNumbers =
            numeric(leftType) && numeric(rightType);
        final boolean valid = switch (binary.operator()) {
            case AND, OR ->
                leftType == BuiltinType.BOOL && rightType == BuiltinType.BOOL;
            case EQUAL, NOT_EQUAL -> unionEquality || compatibleNumbers
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
            case STRING, RAW_STRING -> BuiltinType.STRING;
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
