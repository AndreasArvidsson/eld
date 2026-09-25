package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.BlockItem;
import com.github.andreasarvidsson.eld.parser.BlockStatement;
import com.github.andreasarvidsson.eld.parser.BreakStatement;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.ContinueStatement;
import com.github.andreasarvidsson.eld.parser.Declaration;
import com.github.andreasarvidsson.eld.parser.DeclarationStatement;
import com.github.andreasarvidsson.eld.parser.DoWhileStatement;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.ExpressionStatement;
import com.github.andreasarvidsson.eld.parser.ForEachStatement;
import com.github.andreasarvidsson.eld.parser.ForStatement;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.IfExpression;
import com.github.andreasarvidsson.eld.parser.InterfaceDeclaration;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.NamedTypeNode;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.Program;
import com.github.andreasarvidsson.eld.parser.ReturnStatement;
import com.github.andreasarvidsson.eld.parser.RecordDeclaration;
import com.github.andreasarvidsson.eld.parser.TryStatement;
import com.github.andreasarvidsson.eld.parser.ThrowStatement;
import com.github.andreasarvidsson.eld.parser.CatchClause;
import com.github.andreasarvidsson.eld.parser.Statement;
import com.github.andreasarvidsson.eld.parser.SuperConstructorCall;
import com.github.andreasarvidsson.eld.parser.SwitchExpression;
import com.github.andreasarvidsson.eld.parser.TernaryExpression;
import com.github.andreasarvidsson.eld.parser.TypeNode;
import com.github.andreasarvidsson.eld.parser.TypeAliasDeclaration;
import com.github.andreasarvidsson.eld.parser.UninitializedVariableDeclaration;
import com.github.andreasarvidsson.eld.parser.VariableDeclaration;
import com.github.andreasarvidsson.eld.parser.Visibility;
import com.github.andreasarvidsson.eld.parser.WhileStatement;
import com.github.andreasarvidsson.eld.parser.YieldStatement;

public final class SemanticAnalyzer {
    private final SemanticModel model = new SemanticModel();
    private final SemanticAnalyzerExpressions expressions =
        new SemanticAnalyzerExpressions(this, model);
    private final SemanticAnalyzerDeclarations declarations =
        new SemanticAnalyzerDeclarations(this, model);
    private final SemanticAnalyzerObjects objects =
        new SemanticAnalyzerObjects(this, model);
    private final SemanticAnalyzerStatements statements =
        new SemanticAnalyzerStatements(this, model);
    private final SemanticAnalyzerTypes types =
        new SemanticAnalyzerTypes(model);
    private final Map<ClassType, Scope> classScopes = new HashMap<>();
    private final Map<ClassType, Map<String, FunctionSymbol>> classMethods =
        new HashMap<>();
    private final IdentityHashMap<ObjectExpression, InterfaceType> inferredObjects =
        new IdentityHashMap<>();
    private @Nullable ClassType currentInstance;
    private @Nullable ClassType currentAccessClass;
    private @Nullable ConstructorDeclaration currentConstructor;
    private boolean analyzingConstructorDefault;
    private boolean analyzingSuperArguments;
    private final IdentityHashMap<FunctionSymbol, List<ReturnStatement>> lambdaReturns =
        new IdentityHashMap<>();

    public SemanticModel analyze(final Program program) {
        final Scope builtinScope = new Scope(null);
        builtinScope.declare(BuiltinFunctionSymbol.PRINT);
        builtinScope.declare(BuiltinFunctionSymbol.DIR);
        builtinScope.declare(BuiltinFunctionSymbol.HELP);

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
            analyzeBlockItem(item, context, program);
        }
    }

    public void analyzeBlockStatement(
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
            analyzeBlockItem(item, blockContext, block);
        }
    }

    private void analyzeBlockItem(
        final BlockItem item,
        final SemanticContext context,
        final AstNode parent
    ) {
        switch (item) {
            case Declaration declaration ->
                analyzeDeclaration(declaration, context, parent);
            case Statement statement -> analyzeStatement(statement, context);
        }
    }

    private void analyzeDeclaration(
        final Declaration declaration,
        final SemanticContext context,
        final AstNode parent
    ) {
        switch (declaration) {
            case VariableDeclaration variableDeclaration ->
                analyzeVariableDeclaration(variableDeclaration, context);
            case FunctionDeclaration functionDeclaration ->
                analyzeFunctionDeclaration(
                    functionDeclaration,
                    context,
                    parent
                );
            case InterfaceDeclaration contract ->
                analyzeInterfaceDeclaration(contract, context);
            case ClassDeclaration classDeclaration ->
                analyzeClassDeclaration(classDeclaration, context);
            case RecordDeclaration record -> {
                final ClassDeclaration lowered = RecordLowering.lower(record);
                model.setRecordClass(record, lowered);
                analyzeClassDeclaration(lowered, context);
            }
            case TypeAliasDeclaration alias -> {
                final Type type = types.resolveType(alias.type(), context);
                final TypeAliasSymbol symbol =
                    new TypeAliasSymbol(alias.name(), type);
                context.scope().declare(symbol);
                model.setSymbol(alias.name(), symbol);
            }
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
            case IdentifierDeclaration _ -> throw new SemanticException(
                declaration.range(),
                "Unexpected declaration: %s",
                declaration
            );
        }
    }

    public void analyzeStatement(
        final Statement statement,
        final SemanticContext context
    ) {
        switch (statement) {
            case SuperConstructorCall call -> {
                if (
                    currentConstructor == null || context.function() != null
                        || currentInstance == null
                        || analyzingSuperArguments
                        || analyzingConstructorDefault
                ) {
                    throw new SemanticException(
                        call.range(),
                        "'super(...)' is only allowed in a subclass constructor"
                    );
                }
                final ClassType superclass =
                    model.getSuperclass(currentInstance);
                if (superclass == null) {
                    throw new SemanticException(
                        call.range(),
                        "'super(...)' requires a superclass"
                    );
                }
                final boolean previous = analyzingSuperArguments;
                analyzingSuperArguments = true;
                try {
                    expressions.analyzeConstructorArguments(
                        superclass,
                        call.arguments(),
                        call.range(),
                        context
                    );
                }
                finally {
                    analyzingSuperArguments = previous;
                }
            }
            case DeclarationStatement declarationStatement ->
                analyzeDeclaration(
                    declarationStatement.declaration(),
                    context,
                    declarationStatement
                );
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
            case ThrowStatement thrown -> {
                final Type type = analyzeExpression(thrown.value(), context);
                requireThrowable(type, thrown.value().range());
            }
            case TryStatement guarded -> analyzeTryStatement(guarded, context);
            case ReturnStatement returnStatement ->
                analyzeReturnStatement(returnStatement, context);
            case BlockStatement blockStatement ->
                analyzeBlockStatement(blockStatement, context);
        }
    }

    private static void requireThrowable(final Type type, final Range range) {
        if (!isThrowable(type)) {
            throw new SemanticException(
                range,
                "Expected a JVM throwable type, found %s",
                type
            );
        }
    }

    private static boolean isThrowable(final Type type) {
        if (type instanceof UnionType union) {
            return union.memberTypes()
                .stream()
                .allMatch(SemanticAnalyzer::isThrowable);
        }
        return type instanceof InterfaceType javaType
            && javaType.javaClass() != null
            && Throwable.class.isAssignableFrom(javaType.javaClass());
    }

    private static void requireCatchType(final Type type, final Range range) {
        if (!(type instanceof InterfaceType)) {
            throw new SemanticException(
                range,
                "Expected a JVM throwable type, found %s",
                type
            );
        }
        requireThrowable(type, range);
    }

    private void analyzeTryStatement(
        final TryStatement statement,
        final SemanticContext context
    ) {
        analyzeBlockStatement(statement.body(), context);
        final List<Type> previous = new ArrayList<>();
        for (final CatchClause clause : statement.catches()) {
            final Type type = resolveType(clause.type(), context);
            requireCatchType(type, clause.type().range());
            if (
                previous.stream()
                    .anyMatch(caught -> model.isSubtype(type, caught))
            ) {
                throw new SemanticException(
                    clause.type().range(),
                    "Unreachable catch for %s: an earlier catch handles this type",
                    type
                );
            }
            previous.add(type);
            final Scope scope = new Scope(context.scope());
            final VariableSymbol symbol =
                new VariableSymbol(clause.name(), type, Mutability.CONST);
            scope.declare(symbol);
            model.setSymbol(clause.name(), symbol);
            analyzeBlockStatement(
                clause.body(),
                new SemanticContext(
                    scope,
                    context.function(),
                    context.loopDepth(),
                    context.yields(),
                    context.yieldType()
                )
            );
        }
        if (statement.finallyBody() != null) {
            analyzeBlockStatement(statement.finallyBody(), context);
        }
    }

    private void analyzeClassDeclaration(
        final ClassDeclaration declaration,
        final SemanticContext context
    ) {
        declarations.analyzeClassDeclaration(declaration, context);
    }

    private void analyzeInterfaceDeclaration(
        final InterfaceDeclaration declaration,
        final SemanticContext context
    ) {
        declarations.analyzeInterfaceDeclaration(declaration, context);
    }

    public void analyzeObjectExpression(
        final ObjectExpression object,
        final SemanticContext context,
        final InterfaceType type
    ) {
        objects.analyzeObjectExpression(object, context, type);
    }

    public InterfaceType inferSpreadObject(
        final ObjectExpression object,
        final SemanticContext context
    ) {
        return objects.inferSpreadObject(object, context);
    }

    public @Nullable ClassType classFieldOwner(
        final ClassType type,
        final String name
    ) {
        return objects.classFieldOwner(type, name);
    }

    public @Nullable ClassType classMethodOwner(
        final ClassType type,
        final String name
    ) {
        return objects.classMethodOwner(type, name);
    }

    public @Nullable FunctionSymbol classMethod(
        final ClassType type,
        final String name
    ) {
        return objects.classMethod(type, name);
    }

    public boolean canAccess(
        final ClassType owner,
        final Visibility visibility
    ) {
        return objects.canAccess(owner, visibility);
    }

    public @Nullable ClassType memberOwner(
        final ClassType type,
        final String name
    ) {
        return objects.memberOwner(type, name);
    }

    private void analyzeForStatement(
        final ForStatement statement,
        final SemanticContext context
    ) {
        statements.analyzeForStatement(statement, context);
    }

    private void analyzeForEachStatement(
        final ForEachStatement statement,
        final SemanticContext context
    ) {
        statements.analyzeForEachStatement(statement, context);
    }

    private void analyzeWhileStatement(
        final WhileStatement statement,
        final SemanticContext context
    ) {
        statements.analyzeWhileStatement(statement, context);
    }

    private void analyzeDoWhileStatement(
        final DoWhileStatement statement,
        final SemanticContext context
    ) {
        statements.analyzeDoWhileStatement(statement, context);
    }

    private void analyzeIfExpressionBranches(
        final IfExpression expression,
        final SemanticContext context
    ) {
        statements.analyzeIfExpressionBranches(expression, context);
    }

    private void analyzeContinueStatement(
        final ContinueStatement statement,
        final SemanticContext context
    ) {
        statements.analyzeContinueStatement(statement, context);
    }

    private void analyzeBreakStatement(
        final BreakStatement statement,
        final SemanticContext context
    ) {
        statements.analyzeBreakStatement(statement, context);
    }

    private void analyzeYieldStatement(
        final YieldStatement statement,
        final SemanticContext context
    ) {
        statements.analyzeYieldStatement(statement, context);
    }

    private void analyzeDiscardedExpression(
        final Expression expression,
        final SemanticContext context
    ) {
        statements.analyzeDiscardedExpression(expression, context);
    }

    public Type analyzeSwitchExpression(
        final SwitchExpression expression,
        final SemanticContext context,
        final boolean requireValue
    ) {
        return statements
            .analyzeSwitchExpression(expression, context, requireValue);
    }

    public Type analyzeSwitchExpression(
        final SwitchExpression expression,
        final SemanticContext context,
        final boolean requireValue,
        final @Nullable Type expected
    ) {
        return statements.analyzeSwitchExpression(
            expression,
            context,
            requireValue,
            expected
        );
    }

    public Type analyzeIfExpression(
        final IfExpression expression,
        final SemanticContext context
    ) {
        return statements.analyzeIfExpression(expression, context);
    }

    public Type analyzeIfExpression(
        final IfExpression expression,
        final SemanticContext context,
        final @Nullable Type expected
    ) {
        return statements.analyzeIfExpression(expression, context, expected);
    }

    public Type analyzeTernaryExpression(
        final TernaryExpression expression,
        final SemanticContext context
    ) {
        return statements.analyzeTernaryExpression(expression, context);
    }

    public Type commonBranchType(
        final Type left,
        final Type right,
        final AstNode node
    ) {
        return statements.commonBranchType(left, right, node);
    }

    private void analyzeReturnStatement(
        final ReturnStatement statement,
        final SemanticContext context
    ) {
        statements.analyzeReturnStatement(statement, context);
    }

    private void analyzeVariableDeclaration(
        final VariableDeclaration declaration,
        final SemanticContext context
    ) {
        statements.analyzeVariableDeclaration(declaration, context);
    }

    private void analyzeFunctionDeclaration(
        final FunctionDeclaration declaration,
        final SemanticContext context,
        final AstNode parent
    ) {
        statements.analyzeFunctionDeclaration(declaration, context, parent);
    }

    public void analyzeFunctionBody(
        final FunctionDeclaration declaration,
        final SemanticContext context
    ) {
        statements.analyzeFunctionBody(declaration, context);
    }

    public void analyzeParameterDefault(
        final FunctionParameter parameter,
        final SemanticContext context
    ) {
        statements.analyzeParameterDefault(parameter, context);
    }

    public Type resolveParameterType(
        final FunctionParameter parameter,
        final SemanticContext context
    ) {
        return statements.resolveParameterType(parameter, context);
    }

    public void analyzeVariableDeclaration(
        final VariableDeclaration declaration,
        final SemanticContext context,
        final Scope destination
    ) {
        statements
            .analyzeVariableDeclaration(declaration, context, destination);
    }

    public void registerFunction(
        final FunctionDeclaration declaration,
        final SemanticContext context,
        final Scope destination
    ) {
        statements.registerFunction(declaration, context, destination);
    }

    public void validateInheritedMember(
        final ClassType type,
        final Symbol symbol
    ) {
        objects.validateInheritedMember(type, symbol);
    }

    public Type resolveType(
        final TypeNode typeNode,
        final SemanticContext context
    ) {
        return types.resolveType(typeNode, context);
    }

    public boolean canAssignJavaArgument(
        final Type from,
        final Type to,
        final Expression expression
    ) {
        return types.canAssignJavaArgument(from, to, expression);
    }

    public boolean isMoreSpecificJavaParameter(
        final Type candidate,
        final Type other
    ) {
        return types.isMoreSpecificJavaParameter(candidate, other);
    }

    public @Nullable Type resolveAssignType(
        final Type from,
        final Type to,
        final Expression fromExpression
    ) {
        return types.resolveAssignType(from, to, fromExpression);
    }

    public @Nullable Type commonType(final List<Type> candidates) {
        return types.commonType(candidates);
    }

    public Type resolveNamedType(
        final NamedTypeNode named,
        final SemanticContext context
    ) {
        return types.resolveNamedType(named, context);
    }

    public Type analyzeExpression(
        final Expression expression,
        final SemanticContext context,
        final @Nullable Type expected
    ) {
        return expressions.analyzeExpression(expression, context, expected);
    }

    public Type analyzeExpression(
        final Expression expression,
        final SemanticContext context
    ) {
        return expressions.analyzeExpression(expression, context);
    }

    public Type analyzeExpressionAsCallee(
        final Expression expression,
        final SemanticContext context,
        final boolean callee
    ) {
        return expressions
            .analyzeExpressionAsCallee(expression, context, callee);
    }

    public Type analyzeIdentifierExpression(
        final IdentifierExpression identifier,
        final SemanticContext context
    ) {
        return expressions.analyzeIdentifierExpression(identifier, context);
    }

    public Map<ClassType, Scope> classScopes() {
        return classScopes;
    }

    public Map<ClassType, Map<String, FunctionSymbol>> classMethods() {
        return classMethods;
    }

    public IdentityHashMap<ObjectExpression, InterfaceType> inferredObjects() {
        return inferredObjects;
    }

    public @Nullable ClassType currentAccessClass() {
        return currentAccessClass;
    }

    public void setCurrentAccessClass(final @Nullable ClassType type) {
        currentAccessClass = type;
    }

    public void setCurrentInstance(final @Nullable ClassType type) {
        currentInstance = type;
    }

    public @Nullable ConstructorDeclaration currentConstructor() {
        return currentConstructor;
    }

    public void setCurrentConstructor(
        final @Nullable ConstructorDeclaration constructor
    ) {
        currentConstructor = constructor;
    }

    public @Nullable List<ReturnStatement> lambdaReturns(
        final FunctionSymbol symbol
    ) {
        return lambdaReturns.get(symbol);
    }

    public void setAnalyzingConstructorDefault(final boolean analyzing) {
        analyzingConstructorDefault = analyzing;
    }

    @Nullable
    public Scope classScope(final ClassType type) {
        return classScopes.get(type);
    }

    public boolean isAnalyzingConstructorDefault() {
        return analyzingConstructorDefault;
    }

    public boolean isAnalyzingSuperArguments() {
        return analyzingSuperArguments;
    }

    @Nullable
    public ClassType currentInstance() {
        return currentInstance;
    }

    public boolean isInConstructor() {
        return currentConstructor != null;
    }

    public void setLambdaReturns(
        final FunctionSymbol symbol,
        final List<ReturnStatement> returns
    ) {
        lambdaReturns.put(symbol, returns);
    }

    public void clearLambdaReturns(final FunctionSymbol symbol) {
        lambdaReturns.remove(symbol);
    }

    public static @Nullable BigInteger integerLiteral(
        final Expression expression
    ) {
        return SemanticAnalyzerExpressions.integerLiteral(expression);
    }
}
