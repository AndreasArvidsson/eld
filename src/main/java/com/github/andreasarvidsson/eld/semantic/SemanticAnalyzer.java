package com.github.andreasarvidsson.eld.semantic;

import java.math.BigInteger;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.AstTraversal;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.AwaitExpression;
import com.github.andreasarvidsson.eld.parser.AssignmentExpression;
import com.github.andreasarvidsson.eld.parser.BlockItem;
import com.github.andreasarvidsson.eld.parser.BlockStatement;
import com.github.andreasarvidsson.eld.parser.BreakStatement;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.ContinueStatement;
import com.github.andreasarvidsson.eld.parser.Declaration;
import com.github.andreasarvidsson.eld.parser.DeclarationStatement;
import com.github.andreasarvidsson.eld.parser.DoWhileStatement;
import com.github.andreasarvidsson.eld.parser.DestructuringAssignmentStatement;
import com.github.andreasarvidsson.eld.parser.DestructuringDeclaration;
import com.github.andreasarvidsson.eld.parser.DiscardPattern;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.ExpressionStatement;
import com.github.andreasarvidsson.eld.parser.ForEachStatement;
import com.github.andreasarvidsson.eld.parser.ForStatement;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierPattern;
import com.github.andreasarvidsson.eld.parser.IfExpression;
import com.github.andreasarvidsson.eld.parser.IgnoreStatement;
import com.github.andreasarvidsson.eld.parser.InterfaceDeclaration;
import com.github.andreasarvidsson.eld.parser.LambdaExpression;
import com.github.andreasarvidsson.eld.parser.LiteralExpression;
import com.github.andreasarvidsson.eld.parser.LiteralKind;
import com.github.andreasarvidsson.eld.parser.MapExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.NamedTypeNode;
import com.github.andreasarvidsson.eld.parser.NewExpression;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.PostfixExpression;
import com.github.andreasarvidsson.eld.parser.Program;
import com.github.andreasarvidsson.eld.parser.ReturnStatement;
import com.github.andreasarvidsson.eld.parser.RecordDeclaration;
import com.github.andreasarvidsson.eld.parser.RecordPattern;
import com.github.andreasarvidsson.eld.parser.RecordPatternField;
import com.github.andreasarvidsson.eld.parser.TryStatement;
import com.github.andreasarvidsson.eld.parser.ThrowStatement;
import com.github.andreasarvidsson.eld.parser.ThisExpression;
import com.github.andreasarvidsson.eld.parser.UnaryExpression;
import com.github.andreasarvidsson.eld.parser.UnaryOperator;
import com.github.andreasarvidsson.eld.parser.CatchClause;
import com.github.andreasarvidsson.eld.parser.Statement;
import com.github.andreasarvidsson.eld.parser.SuperConstructorCall;
import com.github.andreasarvidsson.eld.parser.SubscriptExpression;
import com.github.andreasarvidsson.eld.parser.SwitchExpression;
import com.github.andreasarvidsson.eld.parser.TernaryExpression;
import com.github.andreasarvidsson.eld.parser.TypeNode;
import com.github.andreasarvidsson.eld.parser.TuplePattern;
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
    private final List<ConstMethodUse> constMethodUses = new ArrayList<>();
    private final IdentityHashMap<FunctionSymbol, Boolean> mutatingMethods =
        new IdentityHashMap<>();

    private record ConstMethodUse(
        MemberExpression expression, FunctionSymbol function
    ) {
    }

    private record EffectContext(
        @Nullable FunctionSymbol function, @Nullable ClassType construction,
        AstNode scope, FunctionDeclaration declaration,
        Set<ClassType> constructing
    ) {
    }

    private record BoundJavaCallable(
        MemberExpression member, JavaMethodSymbol method
    ) {
    }

    public SemanticModel analyze(final Program program) {
        final Scope builtinScope = new Scope(null);
        builtinScope.declare(BuiltinFunctionSymbol.PRINT);
        builtinScope.declare(BuiltinFunctionSymbol.DIR);
        builtinScope.declare(BuiltinFunctionSymbol.HELP);

        final Scope globalScope = new Scope(builtinScope);
        final SemanticContext context =
            new SemanticContext(globalScope, null, 0);

        analyzeProgram(program, context);
        classifyConstantCallableVariables();
        validateConstantFunctions();
        validateConstMethodUses();

        return model;
    }

    public void recordConstMethodUse(
        final MemberExpression expression,
        final FunctionSymbol function
    ) {
        constMethodUses.add(new ConstMethodUse(expression, function));
    }

    private void validateConstantFunctions() {
        for (final var entry : model.getFunctionDeclarations().entrySet()) {
            final FunctionDeclaration declaration = entry.getValue();
            if (!declaration.constant()) {
                continue;
            }
            for (final FunctionParameter parameter : declaration.parameters()) {
                if (parameter.defaultValue() != null) {
                    validateConstantNode(
                        Objects.requireNonNull(parameter.defaultValue()),
                        new EffectContext(
                            entry.getKey(),
                            null,
                            declaration.body(),
                            declaration,
                            new HashSet<>()
                        )
                    );
                }
            }
            validateConstantNode(
                declaration.body(),
                new EffectContext(
                    entry.getKey(),
                    null,
                    declaration.body(),
                    declaration,
                    new HashSet<>()
                )
            );
        }
    }

    private void classifyConstantCallableVariables() {
        for (final VariableSymbol variable : model.getVariableInitializers()
            .keySet()) {
            if (
                referencesConstantFunction(
                    variable,
                    Collections.newSetFromMap(new IdentityHashMap<>())
                )
            ) {
                model.setConstantCallable(variable);
            }
        }
    }

    private boolean referencesConstantFunction(
        final VariableSymbol variable,
        final Set<VariableSymbol> visiting
    ) {
        if (
            variable.mutability() != Mutability.CONST || !visiting.add(variable)
        ) {
            return false;
        }
        final Expression initializer = model.findVariableInitializer(variable);
        if (initializer == null) {
            return false;
        }
        final Symbol referenced =
            calleeSymbol(
                SemanticAnalyzerExpressionOperations.unwrap(initializer)
            );
        if (referenced instanceof FunctionSymbol function) {
            return constantCallable(function);
        }
        return referenced instanceof VariableSymbol alias
            && referencesConstantFunction(alias, visiting);
    }

    private void validateConstantNode(
        final AstNode node,
        final EffectContext context
    ) {
        final EffectContext active =
            node instanceof LambdaExpression lambda
                ? new EffectContext(
                    model.getLambdaFunction(lambda),
                    null,
                    lambda.body(),
                    context.declaration(),
                    context.constructing()
                )
                : context;
        final Expression written = switch (node) {
            case AssignmentExpression assignment -> assignment.target();
            case PostfixExpression postfix -> postfix.operand();
            case UnaryExpression unary when unary
                .operator() == UnaryOperator.INCREMENT
                || unary.operator() == UnaryOperator.DECREMENT ->
                unary.operand();
            default -> null;
        };
        if (written != null && !isAllowedWrite(written, active)) {
            throw new SemanticException(
                written.range(),
                "Const function '%s' cannot modify non-local data",
                context.declaration().name().name()
            );
        }
        if (node instanceof DestructuringAssignmentStatement assignment) {
            AstTraversal.walk(assignment.pattern(), target -> {
                if (
                    target instanceof IdentifierPattern identifier
                        && !isAllowedWrite(identifier, active)
                ) {
                    throw new SemanticException(
                        identifier.range(),
                        "Const function '%s' cannot modify non-local data",
                        context.declaration().name().name()
                    );
                }
            });
        }
        if (node instanceof CallExpression call) {
            validateConstantCall(call, active);
        }
        if (node instanceof NewExpression creation) {
            validateConstruction(creation, active);
        }
        for (final var component : node.getClass().getRecordComponents()) {
            try {
                final Object value = component.getAccessor().invoke(node);
                if (value instanceof AstNode child) {
                    validateConstantNode(child, active);
                }
                else if (value instanceof Iterable<?> children) {
                    for (final Object child : children) {
                        if (child instanceof AstNode ast) {
                            validateConstantNode(ast, active);
                        }
                    }
                }
            }
            catch (final ReflectiveOperationException exception) {
                throw new IllegalStateException(
                    "Cannot validate const function",
                    exception
                );
            }
        }
    }

    private void validateConstantCall(
        final CallExpression call,
        final EffectContext context
    ) {
        final Expression callee =
            SemanticAnalyzerExpressionOperations.unwrap(call.callee());
        final Symbol symbol = calleeSymbol(callee);
        boolean allowed = false;
        if (symbol instanceof FunctionSymbol function) {
            allowed = constantCallable(function);
        }
        else if (symbol instanceof JavaMethodSymbol method) {
            allowed =
                callee instanceof MemberExpression member
                    && allowedJavaMethod(member, method, context);
        }
        else if (symbol instanceof BuiltinFunctionSymbol builtin) {
            allowed =
                builtin.type() == BuiltinFunctionType.DIR
                    || (builtin.type() == BuiltinFunctionType.ARRAY_SORT
                        && callee instanceof MemberExpression member
                        && isLocallyOwned(member.target(), context)
                        && hasConstantNaturalOrder(member));
        }
        else if (symbol instanceof VariableSymbol variable) {
            allowed = constantLocalCallable(variable, context);
        }
        if (!allowed) {
            throw new SemanticException(
                call.range(),
                "Const function '%s' cannot call non-const function '%s'",
                context.declaration().name().name(),
                symbol != null ? symbol.name() : "<indirect>"
            );
        }
        if (
            symbol instanceof JavaMethodSymbol method
                && callee instanceof MemberExpression member
        ) {
            validateConstantCollectionCallbacks(call, member, method, context);
        }
        else if (symbol instanceof VariableSymbol variable) {
            final BoundJavaCallable bound =
                boundJavaCallable(
                    variable,
                    Collections.newSetFromMap(new IdentityHashMap<>())
                );
            if (bound != null) {
                validateConstantCollectionCallbacks(
                    call,
                    bound.member(),
                    bound.method(),
                    context
                );
            }
        }
    }

    private boolean constantLocalCallable(
        final VariableSymbol variable,
        final EffectContext context
    ) {
        return constantLocalCallable(
            variable,
            context,
            Collections.newSetFromMap(new IdentityHashMap<>())
        );
    }

    private boolean constantLocalCallable(
        final VariableSymbol variable,
        final EffectContext context,
        final Set<VariableSymbol> visiting
    ) {
        if (
            variable.mutability() != Mutability.CONST || !visiting.add(variable)
        ) {
            return false;
        }
        if (model.isConstantCallable(variable)) {
            return true;
        }
        final boolean local =
            (context.function() != null && Objects
                .equals(model.findVariableOwner(variable), context.function()))
                || (context.construction() != null && Objects.equals(
                    model.findConstructorVariableOwner(variable),
                    context.construction()
                ));
        if (!local) {
            return false;
        }
        final Expression initializer = model.findVariableInitializer(variable);
        if (initializer == null) {
            return false;
        }
        final Expression value =
            SemanticAnalyzerExpressionOperations.unwrap(initializer);
        if (value instanceof LambdaExpression) {
            return true;
        }
        final Symbol referenced = calleeSymbol(value);
        if (referenced instanceof FunctionSymbol function) {
            return constantCallable(function);
        }
        if (referenced instanceof VariableSymbol alias) {
            return constantLocalCallable(alias, context, visiting);
        }
        return referenced instanceof JavaMethodSymbol method
            && value instanceof MemberExpression member
            && allowedJavaMethod(member, method, context);
    }

    private @Nullable BoundJavaCallable boundJavaCallable(
        final VariableSymbol variable,
        final Set<VariableSymbol> visiting
    ) {
        if (
            variable.mutability() != Mutability.CONST || !visiting.add(variable)
        ) {
            return null;
        }
        final Expression initializer = model.findVariableInitializer(variable);
        if (initializer == null) {
            return null;
        }
        final Expression value =
            SemanticAnalyzerExpressionOperations.unwrap(initializer);
        final Symbol referenced = calleeSymbol(value);
        if (
            referenced instanceof JavaMethodSymbol method
                && value instanceof MemberExpression member
        ) {
            return new BoundJavaCallable(member, method);
        }
        return referenced instanceof VariableSymbol alias
            ? boundJavaCallable(alias, visiting)
            : null;
    }

    private void validateConstantCollectionCallbacks(
        final CallExpression call,
        final MemberExpression member,
        final JavaMethodSymbol method,
        final EffectContext context
    ) {
        final Type target = model.getMemberOwner(member);
        if (
            !(target instanceof InterfaceType contract)
                || contract.javaClass() == null
                || (!Collection.class.isAssignableFrom(contract.javaClass())
                    && !Map.class.isAssignableFrom(contract.javaClass()))
        ) {
            return;
        }
        validateImplicitCollectionEffects(member, method, contract, context);
        final int callbackIndex = switch (method.name()) {
            case "compute", "computeIfAbsent", "computeIfPresent" -> 1;
            case "merge" -> 2;
            case "forEach", "removeIf", "replaceAll", "sort", "toArray" -> 0;
            default -> -1;
        };
        if (method.name().equals("sort") && call.arguments().isEmpty()) {
            if (hasConstantNaturalOrder(contract)) {
                return;
            }
            throw new SemanticException(
                call.range(),
                "Const function '%s' cannot call non-const natural ordering in '%s'",
                context.declaration().name().name(),
                method.name()
            );
        }
        if (callbackIndex < 0 || callbackIndex >= call.arguments().size()) {
            return;
        }
        final Expression callback = call.arguments().get(callbackIndex);
        if (
            (method.name().equals("sort") && isNullLiteral(callback))
                ? hasConstantNaturalOrder(contract)
                : isConstantCallableExpression(callback, context)
        ) {
            return;
        }
        throw new SemanticException(
            callback.range(),
            "Const function '%s' cannot pass a non-const callback to '%s'",
            context.declaration().name().name(),
            method.name()
        );
    }

    private void validateImplicitCollectionEffects(
        final MemberExpression member,
        final JavaMethodSymbol method,
        final InterfaceType collection,
        final EffectContext context
    ) {
        final Class<?> javaClass =
            Objects.requireNonNull(collection.javaClass());
        final String name = method.name();
        boolean allowed = true;
        if (
            (SortedSet.class.isAssignableFrom(javaClass)
                || SortedMap.class.isAssignableFrom(javaClass)
                || javaClass == Set.class
                || javaClass == Map.class)
                && Set
                    .of(
                        "add",
                        "contains",
                        "containsKey",
                        "get",
                        "headMap",
                        "headSet",
                        "put",
                        "subMap",
                        "subSet",
                        "tailMap",
                        "tailSet"
                    )
                    .contains(name)
        ) {
            allowed = false;
        }
        else if (Set.class.isAssignableFrom(javaClass) && name.equals("add")) {
            allowed = hasConstantEquality(typeArgument(collection, 0));
        }
        else if (name.equals("contains") && isCollection(javaClass)) {
            allowed = hasConstantEquality(typeArgument(collection, 0));
        }
        else if (
            Map.class.isAssignableFrom(javaClass)
                && Set.of("containsKey", "get", "put").contains(name)
        ) {
            allowed = hasConstantEquality(typeArgument(collection, 0));
        }
        else if (
            Map.class.isAssignableFrom(javaClass)
                && name.equals("containsValue")
        ) {
            allowed = hasConstantEquality(typeArgument(collection, 1));
        }
        if (allowed) {
            return;
        }
        throw new SemanticException(
            member.range(),
            "Const function '%s' cannot call collection method '%s' because it may invoke non-const element behavior",
            context.declaration().name().name(),
            name
        );
    }

    private static boolean isCollection(final Class<?> javaClass) {
        return Collection.class.isAssignableFrom(javaClass)
            && !SortedSet.class.isAssignableFrom(javaClass);
    }

    private static Type typeArgument(
        final InterfaceType collection,
        final int index
    ) {
        return collection.typeArguments().size() > index
            ? collection.typeArguments().get(index)
            : BuiltinType.ANY;
    }

    private boolean hasConstantEquality(final Type type) {
        final Type unqualified = ConstType.unwrap(type);
        if (unqualified instanceof ClassType cls) {
            return hasConstantObjectMethod(cls, "equals")
                && hasConstantObjectMethod(cls, "hashCode");
        }
        if (unqualified instanceof InterfaceType contract) {
            final List<ClassType> implementations =
                classMethods.keySet()
                    .stream()
                    .filter(cls -> model.isSubtype(cls, contract))
                    .toList();
            return !implementations.isEmpty()
                && implementations.stream().allMatch(this::hasConstantEquality);
        }
        return unqualified != BuiltinType.ANY;
    }

    private boolean hasConstantObjectMethod(
        final ClassType type,
        final String name
    ) {
        final FunctionSymbol method = classMethod(type, name);
        return method == null || constantCallable(method);
    }

    private boolean isConstantCallableExpression(
        final Expression expression,
        final EffectContext context
    ) {
        final Expression value =
            SemanticAnalyzerExpressionOperations.unwrap(expression);
        if (value instanceof LambdaExpression) {
            return true;
        }
        final Symbol referenced = calleeSymbol(value);
        if (referenced instanceof FunctionSymbol function) {
            return constantCallable(function);
        }
        if (referenced instanceof VariableSymbol variable) {
            return constantLocalCallable(variable, context);
        }
        return referenced instanceof JavaMethodSymbol method
            && value instanceof MemberExpression member
            && allowedJavaMethod(member, method, context);
    }

    private boolean hasConstantNaturalOrder(final MemberExpression member) {
        final Type target = model.getMemberOwner(member);
        if (target instanceof ArrayType array) {
            return hasConstantNaturalOrder(array.elementType());
        }
        return target instanceof InterfaceType contract
            && hasConstantNaturalOrder(contract);
    }

    private boolean hasConstantNaturalOrder(final InterfaceType collection) {
        return !collection.typeArguments().isEmpty()
            && hasConstantNaturalOrder(collection.typeArguments().getFirst());
    }

    private boolean hasConstantNaturalOrder(final Type element) {
        final Type unqualified = ConstType.unwrap(element);
        if (unqualified instanceof ClassType type) {
            final FunctionSymbol compareTo = classMethod(type, "compareTo");
            return compareTo != null && constantCallable(compareTo);
        }
        if (unqualified instanceof InterfaceType contract) {
            final FunctionSymbol compareTo =
                model.getInterface(contract).methods().get("compareTo");
            return compareTo != null && constantCallable(compareTo);
        }
        return model.hasNaturalOrder(unqualified);
    }

    private static boolean isNullLiteral(final Expression expression) {
        final Expression value =
            SemanticAnalyzerExpressionOperations.unwrap(expression);
        return value instanceof LiteralExpression literal
            && literal.kind() == LiteralKind.NULL;
    }

    private boolean allowedJavaMethod(
        final MemberExpression member,
        final JavaMethodSymbol method,
        final EffectContext context
    ) {
        final Type target = model.getMemberOwner(member);
        if (
            target instanceof InterfaceType contract
                && contract.javaClass() != null
                && (Collection.class.isAssignableFrom(contract.javaClass())
                    || Map.class.isAssignableFrom(contract.javaClass()))
        ) {
            return !SemanticAnalyzerExpressions
                .mutatesCollection(contract, method.method())
                || isLocallyOwned(member.target(), context);
        }
        return pureJavaMethod(method);
    }

    private boolean pureJavaMethod(final JavaMethodSymbol symbol) {
        final var method = symbol.method();
        final Class<?> owner = method.getDeclaringClass();
        if (
            method.getName().equals("compareTo")
                && (owner == Byte.class || owner == Short.class
                    || owner == Integer.class
                    || owner == Long.class
                    || owner == Float.class
                    || owner == Double.class
                    || owner == Character.class
                    || owner == Boolean.class
                    || owner == String.class
                    || owner == BigInteger.class)
        ) {
            return true;
        }
        if (Pattern.class.isAssignableFrom(owner)) {
            return true;
        }
        if (owner == Matcher.class) {
            return Modifier.isStatic(method.getModifiers())
                || Set
                    .of(
                        "end",
                        "group",
                        "groupCount",
                        "hasAnchoringBounds",
                        "hasTransparentBounds",
                        "pattern",
                        "regionEnd",
                        "regionStart",
                        "requireEnd",
                        "start",
                        "toMatchResult",
                        "toString"
                    )
                    .contains(method.getName());
        }
        if (Throwable.class.isAssignableFrom(owner)) {
            return Set
                .of(
                    "getCause",
                    "getLocalizedMessage",
                    "getMessage",
                    "getStackTrace",
                    "getSuppressed",
                    "toString"
                )
                .contains(method.getName());
        }
        return false;
    }

    private @Nullable Symbol calleeSymbol(final Expression callee) {
        if (callee instanceof IdentifierExpression identifier) {
            return model.findReference(identifier);
        }
        if (callee instanceof MemberExpression member) {
            return model.findReference(member.member());
        }
        return null;
    }

    private boolean constantCallable(final FunctionSymbol function) {
        final FunctionDeclaration declaration =
            model.getFunctionDeclaration(function);
        if (declaration != null) {
            if (!declaration.constant()) {
                return false;
            }
            final ClassType owner = model.findClassMemberOwner(function);
            return owner == null || declaration.finalMethod()
                || model.getMemberVisibility(function) == Visibility.PRIVATE
                || classMethods.keySet()
                    .stream()
                    .filter(type -> !type.equals(owner))
                    .filter(type -> model.isSubclassOf(type, owner))
                    .map(type -> classMethod(type, function.name()))
                    .filter(Objects::nonNull)
                    .filter(method -> !Objects.equals(method, function))
                    .distinct()
                    .allMatch(this::declaredConstant);
        }
        final InterfaceType owner = interfaceMethodOwner(function);
        if (owner == null) {
            return false;
        }
        final List<FunctionSymbol> implementations =
            classMethods.keySet()
                .stream()
                .filter(type -> model.isSubtype(type, owner))
                .map(type -> classMethod(type, function.name()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return !implementations.isEmpty()
            && implementations.stream().allMatch(this::declaredConstant);
    }

    private boolean declaredConstant(final FunctionSymbol function) {
        final FunctionDeclaration declaration =
            model.getFunctionDeclaration(function);
        return declaration != null && declaration.constant();
    }

    private boolean isAllowedWrite(
        final Expression expression,
        final EffectContext context
    ) {
        final Expression target =
            SemanticAnalyzerExpressionOperations.unwrap(expression);
        if (target instanceof IdentifierExpression identifier) {
            if (
                !(model.findReference(
                    identifier
                ) instanceof VariableSymbol variable)
            ) {
                return false;
            }
            return isLocalBinding(variable, context)
                || isConstructedField(variable, context);
        }
        if (target instanceof MemberExpression member) {
            return isConstructedReceiver(member.target(), context)
                || isLocallyOwned(member.target(), context);
        }
        return target instanceof SubscriptExpression subscript
            && isLocallyOwned(subscript.target(), context);
    }

    private boolean isAllowedWrite(
        final IdentifierPattern pattern,
        final EffectContext context
    ) {
        final Symbol symbol = model.getPatternSymbol(pattern);
        return symbol instanceof VariableSymbol variable
            && (isLocalBinding(variable, context)
                || isConstructedField(variable, context));
    }

    private boolean isLocalBinding(
        final VariableSymbol variable,
        final EffectContext context
    ) {
        return (context.function() != null && Objects
            .equals(model.findVariableOwner(variable), context.function()))
            || (context.construction() != null && Objects.equals(
                model.findConstructorVariableOwner(variable),
                context.construction()
            ));
    }

    private boolean isConstructedField(
        final VariableSymbol variable,
        final EffectContext context
    ) {
        final ClassType fieldOwner = model.findClassMemberOwner(variable);
        return context.construction() != null && fieldOwner != null
            && (Objects.equals(context.construction(), fieldOwner)
                || model.isSubclassOf(context.construction(), fieldOwner));
    }

    private boolean isConstructedReceiver(
        final Expression expression,
        final EffectContext context
    ) {
        return context.construction() != null && isThisExpression(expression);
    }

    private boolean isLocallyOwned(
        final Expression expression,
        final EffectContext context
    ) {
        final Expression value =
            SemanticAnalyzerExpressionOperations.unwrap(expression);
        if (
            value instanceof IdentifierExpression identifier && model
                .findReference(identifier) instanceof VariableSymbol variable
        ) {
            return isLocallyOwnedVariable(
                variable,
                context,
                Collections.newSetFromMap(new IdentityHashMap<>())
            );
        }
        return value instanceof NewExpression
            || value instanceof ArrayExpression
            || value instanceof MapExpression
            || value instanceof ObjectExpression;
    }

    private boolean isLocallyOwnedVariable(
        final VariableSymbol variable,
        final EffectContext context,
        final Set<VariableSymbol> visiting
    ) {
        if (!isLocalBinding(variable, context) || !visiting.add(variable)) {
            return false;
        }
        final Expression initializer = model.findVariableInitializer(variable);
        final boolean fresh =
            initializer != null && isFreshValue(initializer, context, visiting);
        final boolean reassigned =
            AstTraversal.anyMatch(
                context.scope(),
                node -> (node instanceof AssignmentExpression assignment
                    && Objects
                        .equals(directVariable(assignment.target()), variable))
                    || (node instanceof DestructuringAssignmentStatement destructuring
                        && patternWritesVariable(
                            destructuring.pattern(),
                            variable
                        ))
            );
        visiting.remove(variable);
        return fresh && !reassigned;
    }

    private boolean isFreshValue(
        final Expression expression,
        final EffectContext context,
        final Set<VariableSymbol> visiting
    ) {
        final Expression value =
            SemanticAnalyzerExpressionOperations.unwrap(expression);
        if (
            value instanceof NewExpression || value instanceof ArrayExpression
                || value instanceof MapExpression
                || value instanceof ObjectExpression
        ) {
            return true;
        }
        return value instanceof IdentifierExpression identifier
            && model
                .findReference(identifier) instanceof VariableSymbol variable
            && isLocallyOwnedVariable(variable, context, visiting);
    }

    private @Nullable VariableSymbol directVariable(
        final Expression expression
    ) {
        final Expression target =
            SemanticAnalyzerExpressionOperations.unwrap(expression);
        return target instanceof IdentifierExpression identifier && model
            .findReference(identifier) instanceof VariableSymbol variable
                ? variable
                : null;
    }

    private void validateConstruction(
        final NewExpression creation,
        final EffectContext context
    ) {
        final Symbol symbol = model.findReference(creation.className());
        if (!(symbol instanceof ClassSymbol created)) {
            return;
        }
        validateConstruction(created.type(), context);
    }

    private void validateConstruction(
        final ClassType type,
        final EffectContext context
    ) {
        if (!context.constructing().add(type)) {
            return;
        }
        try {
            final ClassType superclass = model.getSuperclass(type);
            if (superclass != null) {
                validateConstruction(superclass, context);
            }
            final ClassDeclaration declaration =
                model.findClassDeclaration(type);
            if (declaration == null) {
                return;
            }
            for (final var member : declaration.members()) {
                if (member.declaration() instanceof VariableDeclaration field) {
                    validateConstantNode(
                        field.initializer(),
                        new EffectContext(
                            null,
                            type,
                            field.initializer(),
                            context.declaration(),
                            context.constructing()
                        )
                    );
                }
                else if (
                    member
                        .declaration() instanceof ConstructorDeclaration constructor
                ) {
                    final EffectContext constructorContext =
                        new EffectContext(
                            null,
                            type,
                            constructor.body(),
                            context.declaration(),
                            context.constructing()
                        );
                    for (final FunctionParameter parameter : constructor
                        .parameters()) {
                        if (parameter.defaultValue() != null) {
                            validateConstantNode(
                                Objects
                                    .requireNonNull(parameter.defaultValue()),
                                constructorContext
                            );
                        }
                    }
                    validateConstantNode(
                        constructor.body(),
                        constructorContext
                    );
                }
            }
        }
        finally {
            context.constructing().remove(type);
        }
    }

    private void validateConstMethodUses() {
        for (final ConstMethodUse use : constMethodUses) {
            if (
                mutatesReceiver(
                    use.function(),
                    Collections.newSetFromMap(new IdentityHashMap<>())
                )
            ) {
                throw new SemanticException(
                    use.expression().range(),
                    "Cannot call mutating method '%s' through a const object",
                    use.function().name()
                );
            }
        }
    }

    private boolean mutatesReceiver(
        final FunctionSymbol function,
        final Set<FunctionSymbol> visiting
    ) {
        final Boolean cached = mutatingMethods.get(function);
        if (cached != null) {
            return cached;
        }
        final FunctionDeclaration declaration =
            model.getFunctionDeclaration(function);
        if (!visiting.add(function)) {
            return false;
        }
        if (declaration == null) {
            final InterfaceType owner = interfaceMethodOwner(function);
            if (owner == null) {
                visiting.remove(function);
                return true;
            }
            final boolean mutates =
                classMethods.keySet()
                    .stream()
                    .filter(type -> model.isSubtype(type, owner))
                    .map(type -> classMethod(type, function.name()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .anyMatch(method -> mutatesReceiver(method, visiting));
            visiting.remove(function);
            mutatingMethods.put(function, mutates);
            return mutates;
        }
        final boolean[] mutates = {false};
        AstTraversal.walk(declaration.body(), node -> {
            if (mutates[0]) {
                return;
            }
            if (
                node instanceof AssignmentExpression assignment
                    && (receiverField(assignment.target())
                        || containsThisExpression(assignment.value()))
            ) {
                mutates[0] = true;
                return;
            }
            if (
                node instanceof DestructuringAssignmentStatement assignment
                    && (patternWritesReceiverField(assignment.pattern())
                        || containsThisExpression(assignment.value()))
            ) {
                mutates[0] = true;
                return;
            }
            if (
                node instanceof PostfixExpression postfix
                    && receiverField(postfix.operand())
            ) {
                mutates[0] = true;
                return;
            }
            if (
                node instanceof UnaryExpression unary
                    && (unary.operator() == UnaryOperator.INCREMENT
                        || unary.operator() == UnaryOperator.DECREMENT)
                    && receiverField(unary.operand())
            ) {
                mutates[0] = true;
                return;
            }
            if (node instanceof CallExpression call) {
                final FunctionSymbol called = receiverMethod(call.callee());
                if (
                    (called != null && mutatesReceiver(called, visiting))
                        || call.arguments()
                            .stream()
                            .anyMatch(this::containsThisExpression)
                ) {
                    mutates[0] = true;
                }
                return;
            }
            if (
                node instanceof ReturnStatement returned
                    && returned.value() != null
                    && containsThisExpression(returned.value())
            ) {
                mutates[0] = true;
                return;
            }
            if (
                node instanceof VariableDeclaration variable
                    && containsThisExpression(variable.initializer())
            ) {
                mutates[0] = true;
            }
        });
        final ClassType owner = model.findClassMemberOwner(function);
        if (owner != null && !mutates[0]) {
            mutates[0] =
                classMethods.keySet()
                    .stream()
                    .filter(type -> !type.equals(owner))
                    .filter(type -> model.isSubclassOf(type, owner))
                    .map(type -> classMethod(type, function.name()))
                    .filter(Objects::nonNull)
                    .filter(method -> !Objects.equals(method, function))
                    .distinct()
                    .anyMatch(method -> mutatesReceiver(method, visiting));
        }
        visiting.remove(function);
        mutatingMethods.put(function, mutates[0]);
        return mutates[0];
    }

    private @Nullable InterfaceType interfaceMethodOwner(
        final FunctionSymbol function
    ) {
        return model.getInterfaceTypes()
            .stream()
            .filter(
                type -> model.getInterface(type)
                    .methods()
                    .values()
                    .stream()
                    .anyMatch(method -> Objects.equals(method, function))
            )
            .findFirst()
            .orElse(null);
    }

    private boolean receiverField(final Expression expression) {
        final Expression target =
            SemanticAnalyzerExpressionOperations.unwrap(expression);
        if (target instanceof MemberExpression member) {
            return isThisExpression(
                member.target()
            ) && model.getReference(member.member()) instanceof VariableSymbol;
        }
        return target instanceof IdentifierExpression identifier
            && model.findReference(identifier) instanceof VariableSymbol field
            && model.findClassMemberOwner(field) != null;
    }

    private boolean patternWritesReceiverField(final AstNode pattern) {
        return AstTraversal.anyMatch(
            pattern,
            node -> node instanceof IdentifierPattern identifier && model
                .getPatternSymbol(identifier) instanceof VariableSymbol field
                && model.findClassMemberOwner(field) != null
        );
    }

    private boolean patternWritesVariable(
        final AstNode pattern,
        final VariableSymbol variable
    ) {
        return AstTraversal.anyMatch(
            pattern,
            node -> node instanceof IdentifierPattern identifier
                && Objects.equals(model.getPatternSymbol(identifier), variable)
        );
    }

    private @Nullable FunctionSymbol receiverMethod(
        final Expression expression
    ) {
        final Expression callee =
            SemanticAnalyzerExpressionOperations.unwrap(expression);
        if (
            callee instanceof MemberExpression member
                && isThisExpression(member.target())
                && model.getReference(
                    member.member()
                ) instanceof FunctionSymbol method
        ) {
            return method;
        }
        if (
            callee instanceof IdentifierExpression identifier
                && model
                    .findReference(identifier) instanceof FunctionSymbol method
                && model.findClassMemberOwner(method) != null
        ) {
            return method;
        }
        return null;
    }

    private boolean isThisExpression(final Expression expression) {
        return SemanticAnalyzerExpressionOperations
            .unwrap(expression) instanceof ThisExpression;
    }

    private boolean containsThisExpression(final Expression expression) {
        return containsThisValue(expression);
    }

    private boolean containsThisValue(final AstNode node) {
        if (node instanceof ThisExpression) {
            return true;
        }
        // A member value is distinct from the receiver used to obtain it.
        if (node instanceof MemberExpression) {
            return false;
        }
        for (final var component : node.getClass().getRecordComponents()) {
            try {
                final Object value = component.getAccessor().invoke(node);
                if (
                    value instanceof AstNode child && containsThisValue(child)
                ) {
                    return true;
                }
                if (value instanceof Iterable<?> children) {
                    for (final Object child : children) {
                        if (
                            child instanceof AstNode ast
                                && containsThisValue(ast)
                        ) {
                            return true;
                        }
                    }
                }
            }
            catch (final ReflectiveOperationException exception) {
                throw new IllegalStateException(
                    "Cannot inspect receiver escape",
                    exception
                );
            }
        }
        return false;
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
            case DestructuringDeclaration destructuring ->
                analyzeDestructuringDeclaration(destructuring, context);
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
            case ConstructorDeclaration _,UninitializedVariableDeclaration _,IdentifierDeclaration _ ->
                throw new IllegalStateException(
                    "Unexpected declaration in executable context: "
                        + declaration
                );
        }
    }

    private void analyzeDestructuringDeclaration(
        final DestructuringDeclaration declaration,
        final SemanticContext context
    ) {
        final Type source =
            expressions.analyzeExpression(declaration.initializer(), context);
        if (source == BuiltinType.VOID) {
            throw new SemanticException(
                declaration.initializer().range(),
                "A destructuring initializer must produce a value"
            );
        }
        analyzePattern(
            declaration.pattern(),
            source,
            context,
            declaration.mutability(),
            true
        );
    }

    private void analyzeDestructuringAssignment(
        final DestructuringAssignmentStatement assignment,
        final SemanticContext context
    ) {
        final Type source =
            expressions.analyzeExpression(assignment.value(), context);
        analyzePattern(
            assignment.pattern(),
            source,
            context,
            Mutability.VAR,
            false
        );
    }

    private void analyzePattern(
        final com.github.andreasarvidsson.eld.parser.Pattern pattern,
        final Type source,
        final SemanticContext context,
        final Mutability mutability,
        final boolean declaration
    ) {
        if (pattern instanceof DiscardPattern) {
            return;
        }
        if (pattern instanceof IdentifierPattern identifier) {
            analyzeIdentifierPattern(
                identifier,
                source,
                context,
                mutability,
                declaration
            );
            return;
        }
        if (pattern instanceof TuplePattern tuple) {
            final Type valueType = ConstType.unwrap(source);
            if (!(valueType instanceof TupleType type)) {
                throw new SemanticException(
                    pattern.range(),
                    "Expected tuple, found %s",
                    source
                );
            }
            if (tuple.elements().size() != type.elementTypes().size()) {
                throw new SemanticException(
                    pattern.range(),
                    "Expected tuple with %s elements, found %s",
                    tuple.elements().size(),
                    type.elementTypes().size()
                );
            }
            for (int i = 0; i < tuple.elements().size(); i++) {
                analyzePattern(
                    tuple.elements().get(i),
                    type.elementTypes().get(i),
                    context,
                    mutability,
                    declaration
                );
            }
            return;
        }
        final RecordPattern record = (RecordPattern) pattern;
        final Type selected = ConstType.unwrap(source);
        if (
            !(selected instanceof ClassType recordType)
                || model.findRecordDeclaration(recordType) == null
        ) {
            throw new SemanticException(
                record.range(),
                "Expected record, found %s",
                source
            );
        }
        model.setRecordPatternType(record, recordType);
        final RecordDeclaration recordDeclaration =
            Objects.requireNonNull(model.findRecordDeclaration(recordType));
        final Map<String, Type> components = new HashMap<>();
        for (final var parameter : recordDeclaration.parameters()) {
            components.put(
                parameter.name().name(),
                model.getResolvedType(parameter.type())
            );
        }
        final Set<String> selectedComponents = new HashSet<>();
        for (final RecordPatternField field : record.fields()) {
            final String name = field.component().name();
            final Type componentType = components.get(name);
            if (componentType == null) {
                throw new SemanticException(
                    field.component().range(),
                    "Unknown record component '%s' on %s",
                    name,
                    recordType
                );
            }
            if (!selectedComponents.add(name)) {
                throw new SemanticException(
                    field.component().range(),
                    "Duplicate record component '%s'",
                    name
                );
            }
            analyzePattern(
                field.target(),
                componentType,
                context,
                mutability,
                declaration
            );
        }
    }

    private void analyzeIdentifierPattern(
        final IdentifierPattern pattern,
        final Type source,
        final SemanticContext context,
        final Mutability mutability,
        final boolean declaration
    ) {
        final Symbol symbol;
        final Type conversionType;
        if (declaration) {
            final @Nullable TypeNode explicitType = pattern.type();
            final Type target =
                explicitType == null
                    ? source
                    : types.resolveType(explicitType, context);
            conversionType = requirePatternAssignment(source, target, pattern);
            final VariableSymbol variable =
                new VariableSymbol(pattern.name(), target, mutability);
            context.scope().declare(variable);
            model.setSymbol(pattern.name(), variable);
            model.setVariableOwner(variable, context.function());
            if (
                context.function() == null && currentConstructor != null
                    && currentInstance != null
            ) {
                model.setConstructorVariableOwner(variable, currentInstance);
            }
            symbol = variable;
        }
        else {
            final @Nullable Symbol resolved =
                context.scope().resolve(pattern.name().name());
            if (resolved == null) {
                throw new SemanticException(
                    pattern.name().range(),
                    "Undefined identifier: '%s'",
                    pattern.name().name()
                );
            }
            if (
                !(resolved instanceof VariableSymbol variable)
                    || variable.mutability() != Mutability.VAR
            ) {
                throw new SemanticException(
                    pattern.name().range(),
                    "Expression is not writable"
                );
            }
            conversionType =
                requirePatternAssignment(source, variable.type(), pattern);
            symbol = variable;
            model.setReference(pattern, symbol);
        }
        model.setPatternBinding(pattern, symbol, source, conversionType);
    }

    private Type requirePatternAssignment(
        final Type source,
        final Type target,
        final IdentifierPattern pattern
    ) {
        final IdentifierExpression extracted =
            new IdentifierExpression(pattern.name().name(), pattern.range());
        model.setExpressionType(extracted, source);
        if (types.resolveAssignType(source, target, extracted) == null) {
            throw new SemanticException(
                pattern.range(),
                "Cannot assign %s to %s",
                source,
                target
            );
        }
        final Type effective = model.getEffectiveType(extracted);
        return effective instanceof UnionType
            ? model.getUnionMemberType(extracted)
            : effective;
    }

    public void analyzeStatement(
        final Statement statement,
        final SemanticContext context
    ) {
        switch (statement) {
            case DestructuringAssignmentStatement destructuring ->
                analyzeDestructuringAssignment(destructuring, context);
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
            case IgnoreStatement ignored ->
                analyzeExpression(ignored.expression(), context);
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

    static void requireThrowable(final Type type, final Range range) {
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
        return ConstType.unwrap(type) instanceof InterfaceType javaType
            && javaType.javaClass() != null
            && Throwable.class.isAssignableFrom(javaType.javaClass());
    }

    private static void requireCatchType(final Type type, final Range range) {
        if (!(ConstType.unwrap(type) instanceof InterfaceType)) {
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
            model.setVariableOwner(symbol, context.function());
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
            final @Nullable AwaitExpression awaited =
                findAwait(statement.finallyBody());
            if (awaited != null) {
                throw new SemanticException(
                    awaited.range(),
                    "Await is not yet supported inside a finally block"
                );
            }
            analyzeBlockStatement(statement.finallyBody(), context);
        }
    }

    private static @Nullable AwaitExpression findAwait(final AstNode node) {
        final AwaitExpression[] result = {null};
        AstTraversal.walk(node, child -> {
            if (result[0] == null && child instanceof AwaitExpression awaited) {
                result[0] = awaited;
            }
        });
        return result[0];
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
