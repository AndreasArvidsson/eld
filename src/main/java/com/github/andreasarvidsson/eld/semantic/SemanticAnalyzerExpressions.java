package com.github.andreasarvidsson.eld.semantic;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.Range;
import com.github.andreasarvidsson.eld.runtime.EldApi;
import com.github.andreasarvidsson.eld.runtime.EldPromise;
import com.github.andreasarvidsson.eld.runtime.PromiseSource;
import com.github.andreasarvidsson.eld.parser.ArrayExpression;
import com.github.andreasarvidsson.eld.parser.ArraySpread;
import com.github.andreasarvidsson.eld.parser.AwaitExpression;
import com.github.andreasarvidsson.eld.parser.AssignmentExpression;
import com.github.andreasarvidsson.eld.parser.AstTraversal;
import com.github.andreasarvidsson.eld.parser.BinaryExpression;
import com.github.andreasarvidsson.eld.parser.BlockStatement;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.FormatStringExpression;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.GroupingExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.IfExpression;
import com.github.andreasarvidsson.eld.parser.LambdaExpression;
import com.github.andreasarvidsson.eld.parser.LiteralExpression;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.MapExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.NamedArgumentExpression;
import com.github.andreasarvidsson.eld.parser.NamedTypeNode;
import com.github.andreasarvidsson.eld.parser.NewExpression;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.PostfixExpression;
import com.github.andreasarvidsson.eld.parser.ReturnStatement;
import com.github.andreasarvidsson.eld.parser.SliceExpression;
import com.github.andreasarvidsson.eld.parser.SubscriptExpression;
import com.github.andreasarvidsson.eld.parser.SwitchExpression;
import com.github.andreasarvidsson.eld.parser.TernaryExpression;
import com.github.andreasarvidsson.eld.parser.ThisExpression;
import com.github.andreasarvidsson.eld.parser.TupleExpression;
import com.github.andreasarvidsson.eld.parser.UnaryExpression;
import com.github.andreasarvidsson.eld.parser.Visibility;

public final class SemanticAnalyzerExpressions {
    private static final Set<String> MUTATING_COLLECTION_METHODS =
        Set.of(
            "add",
            "addAll",
            "addFirst",
            "addLast",
            "clear",
            "compute",
            "computeIfAbsent",
            "computeIfPresent",
            "merge",
            "offer",
            "offerFirst",
            "offerLast",
            "poll",
            "pollFirst",
            "pollLast",
            "pop",
            "push",
            "put",
            "putAll",
            "putFirst",
            "putIfAbsent",
            "putLast",
            "remove",
            "removeAll",
            "removeFirst",
            "removeIf",
            "removeLast",
            "replace",
            "replaceAll",
            "retainAll",
            "set",
            "sort"
        );
    private static final Set<String> COLLECTION_VIEW_METHODS =
        Set.of(
            "descendingMap",
            "descendingSet",
            "entrySet",
            "headMap",
            "headSet",
            "keySet",
            "navigableKeySet",
            "reversed",
            "subList",
            "subMap",
            "subSet",
            "tailMap",
            "tailSet",
            "values"
        );
    private final SemanticAnalyzer analyzer;
    private final SemanticModel model;
    private final SemanticAnalyzerExpectedExpressions expectedExpressions;
    private final SemanticAnalyzerExpressionOperations operations;
    private boolean analyzingCallee;
    private int callArity = -1;
    private List<Expression> javaCallArguments = List.of();

    public SemanticAnalyzerExpressions(
        final SemanticAnalyzer analyzer,
        final SemanticModel model
    ) {
        this.analyzer = analyzer;
        this.model = model;
        this.expectedExpressions =
            new SemanticAnalyzerExpectedExpressions(this.analyzer, this, model);
        this.operations =
            new SemanticAnalyzerExpressionOperations(
                this.analyzer,
                this,
                model
            );
    }

    public Type analyzeExpressionAsCallee(
        final Expression expression,
        final SemanticContext context,
        final boolean callee
    ) {
        final boolean previous = analyzingCallee;
        analyzingCallee = callee;
        try {
            return analyzeExpression(expression, context);
        }
        finally {
            analyzingCallee = previous;
        }
    }

    public Type analyzeExpression(
        final Expression expression,
        final SemanticContext context,
        final @Nullable Type expected
    ) {
        return expectedExpressions
            .analyzeExpression(expression, context, expected);
    }

    public Type analyzeExpression(
        final Expression expression,
        final SemanticContext context
    ) {

        final Type type = switch (expression) {
            case AwaitExpression awaited -> {
                final FunctionSymbol function = context.function();
                if (function == null || !model.isAsync(function)) {
                    throw new SemanticException(
                        awaited.range(),
                        "Await is only allowed inside an async function"
                    );
                }
                final Type operand =
                    analyzeExpression(awaited.expression(), context);
                if (!(operand instanceof PromiseType promise)) {
                    throw new SemanticException(
                        awaited.expression().range(),
                        "Expected Promise<T>, found %s",
                        operand
                    );
                }
                yield promise.valueType();
            }
            case MemberExpression member -> {
                final boolean memberCallee = analyzingCallee;
                final Type target;
                analyzingCallee = false;
                try {
                    if (
                        member
                            .target() instanceof IdentifierExpression identifier
                            && context.scope()
                                .resolve(identifier.name()) == null
                            && JavaTypes.findClass(identifier.name()) != null
                    ) {
                        final JavaClassSymbol symbol =
                            new JavaClassSymbol(
                                JavaTypes.type(identifier.name(), List.of()),
                                identifier.range()
                            );
                        model.setReference(identifier, symbol);
                        model.setExpressionType(identifier, symbol.type());
                        target = symbol.type();
                    }
                    else {
                        target = analyzeExpression(member.target(), context);
                    }
                }
                finally {
                    analyzingCallee = memberCallee;
                }
                final Type memberTarget = ConstType.unwrap(target);
                if (memberTarget instanceof PromiseSourceType source) {
                    final String name = member.member().name();
                    if (
                        eldApiMethod(PromiseSource.class, name, false) == null
                    ) {
                        throw new SemanticException(
                            member.member().range(),
                            "Unknown member '%s' of %s",
                            name,
                            source
                        );
                    }
                    final IdentifierDeclaration declaration =
                        new IdentifierDeclaration(
                            name,
                            member.member().range()
                        );
                    final Symbol symbol;
                    if (name.equals("promise") && !analyzingCallee) {
                        symbol =
                            new VariableSymbol(
                                declaration,
                                new PromiseType(source.valueType()),
                                Mutability.CONST
                            );
                    }
                    else if (name.equals("resolve") && analyzingCallee) {
                        symbol =
                            new FunctionSymbol(
                                declaration,
                                new FunctionType(
                                    source.valueType() == BuiltinType.VOID
                                        ? List.of()
                                        : List.of(source.valueType()),
                                    BuiltinType.VOID
                                )
                            );
                    }
                    else if (name.equals("reject") && analyzingCallee) {
                        symbol =
                            new FunctionSymbol(
                                declaration,
                                new FunctionType(
                                    List.of(
                                        new InterfaceType(
                                            "Throwable",
                                            List.of(),
                                            Throwable.class
                                        )
                                    ),
                                    BuiltinType.VOID
                                )
                            );
                    }
                    else {
                        throw new SemanticException(
                            member.member().range(),
                            "Unknown member '%s' of %s",
                            name,
                            source
                        );
                    }
                    if (symbol instanceof FunctionSymbol function) {
                        model.setFunctionParameters(function, List.of());
                    }
                    model.setMemberOwner(member, source);
                    model.setReference(member.member(), symbol);
                    model.setExpressionType(member.member(), symbol.type());
                    yield symbol.type();
                }
                if (memberTarget instanceof PromiseType promise) {
                    final String name = member.member().name();
                    if (eldApiMethod(EldPromise.class, name, false) == null) {
                        throw new SemanticException(
                            member.member().range(),
                            "Unknown member '%s' of %s",
                            name,
                            promise
                        );
                    }
                    throw new IllegalStateException(
                        "Unsupported Promise API member: " + name
                    );
                }
                if (
                    memberTarget instanceof ArrayType array
                        && member.member().name().equals("sort")
                ) {
                    if (target instanceof ConstType) {
                        throw new SemanticException(
                            member.range(),
                            "Cannot sort a const array"
                        );
                    }
                    final Type element = array.elementType();
                    if (!analyzingCallee) {
                        throw new SemanticException(
                            member.range(),
                            "Array sorting must be called with sort()"
                        );
                    }
                    if (
                        element == BuiltinType.BOOL
                            || !model.hasNaturalOrder(element)
                    ) {
                        throw new SemanticException(
                            member.range(),
                            "Array sorting requires a supported naturally ordered element type, found %s",
                            element
                        );
                    }
                    model.setMemberOwner(member, array);
                    model.setReference(
                        member.member(),
                        new BuiltinFunctionSymbol(
                            "sort",
                            BuiltinFunctionType.ARRAY_SORT
                        )
                    );
                    model.setExpressionType(
                        member.member(),
                        BuiltinFunctionType.ARRAY_SORT
                    );
                    yield BuiltinFunctionType.ARRAY_SORT;
                }
                final InterfaceType javaTarget =
                    memberTarget instanceof InterfaceType contract
                        && contract.javaClass() != null
                            ? contract
                            : JavaTypes.boxedClass(memberTarget) != null
                                ? new InterfaceType(
                                    memberTarget.toString(),
                                    List.of(),
                                    JavaTypes.boxedClass(memberTarget)
                                )
                                : null;
                if (javaTarget != null) {
                    List<JavaMethodSymbol> candidates =
                        JavaTypes.methods(
                            javaTarget,
                            member.member().name(),
                            callArity,
                            member.range(),
                            member
                                .target() instanceof IdentifierExpression identifier
                                && model.getReference(
                                    identifier
                                ) instanceof JavaClassSymbol
                        );
                    if (candidates.size() > 1 && analyzingCallee) {
                        final List<Expression> supplied = javaCallArguments;
                        final List<Type> arguments =
                            supplied.stream()
                                .map(
                                    argument -> analyzeExpression(
                                        argument,
                                        context
                                    )
                                )
                                .toList();
                        candidates = candidates.stream().filter(candidate -> {
                            for (int i = 0; i < arguments.size(); i++) {
                                if (
                                    !analyzer.canAssignJavaArgument(
                                        arguments.get(i),
                                        candidate.type()
                                            .parameterTypes()
                                            .get(i),
                                        supplied.get(i)
                                    )
                                ) {
                                    return false;
                                }
                            }
                            return true;
                        }).toList();
                    }
                    if (candidates.size() != 1) {
                        throw new SemanticException(
                            member.range(),
                            candidates.isEmpty()
                                ? "Unknown Java method '%s' with %s arguments on %s"
                                : "Ambiguous Java method '%s' with %s arguments on %s",
                            member.member().name(),
                            callArity,
                            target
                        );
                    }
                    if (
                        member.member().name().equals("sort") && callArity == 0
                    ) {
                        final Type element =
                            javaTarget.typeArguments().getFirst();
                        if (!model.hasNaturalOrder(element)) {
                            throw new SemanticException(
                                member.range(),
                                "Natural sorting requires %s to implement Comparable<%s>",
                                element,
                                element
                            );
                        }
                    }
                    final JavaMethodSymbol symbol = candidates.getFirst();
                    if (
                        target instanceof ConstType
                            && mutatesCollection(javaTarget, symbol.method())
                    ) {
                        throw new SemanticException(
                            member.range(),
                            "Cannot call mutating method '%s' through a const collection",
                            symbol.name()
                        );
                    }
                    model.setMemberOwner(member, memberTarget);
                    model.setReference(member.member(), symbol);
                    final FunctionType exposedType =
                        collectionMethodType(target, symbol);
                    model.setExpressionType(member.member(), exposedType);
                    yield exposedType;
                }
                if (memberTarget instanceof InterfaceType contract) {
                    final InterfaceContract members =
                        model.getInterface(contract);
                    Symbol symbol =
                        analyzingCallee
                            ? members.methods().get(member.member().name())
                            : members.fields().get(member.member().name());
                    if (symbol == null) {
                        symbol = members.fields().get(member.member().name());
                    }
                    if (symbol == null) {
                        symbol = members.methods().get(member.member().name());
                    }
                    if (symbol == null) {
                        throw new SemanticException(
                            member.member().range(),
                            "Unknown member '%s' of interface %s",
                            member.member().name(),
                            contract
                        );
                    }
                    model.setMemberOwner(member, contract);
                    model.setReference(member.member(), symbol);
                    model.setExpressionType(member.member(), symbol.type());
                    if (
                        target instanceof ConstType
                            && symbol instanceof FunctionSymbol function
                    ) {
                        analyzer.recordConstMethodUse(member, function);
                    }
                    yield symbol.type();
                }
                if (!(memberTarget instanceof ClassType classType)) {
                    throw new SemanticException(
                        member.target().range(),
                        "Member access requires a class instance"
                    );
                }
                final ClassType methodOwner =
                    analyzingCallee
                        ? analyzer
                            .classMethodOwner(classType, member.member().name())
                        : null;
                final ClassType fieldOwner =
                    analyzer.classFieldOwner(classType, member.member().name());
                final ClassType owner =
                    methodOwner != null
                        ? methodOwner
                        : fieldOwner != null
                            ? fieldOwner
                            : analyzer
                                .memberOwner(classType, member.member().name());
                final Scope scope =
                    owner == null ? null : analyzer.classScope(owner);
                final Symbol symbol =
                    methodOwner != null
                        ? analyzer
                            .classMethod(classType, member.member().name())
                        : scope == null
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
                model.setMemberOwner(member, Objects.requireNonNull(owner));
                if (
                    !analyzer.canAccess(
                        Objects.requireNonNull(owner),
                        model.getMemberVisibility(symbol)
                    )
                ) {
                    throw new SemanticException(
                        member.member().range(),
                        "Member '%s' of class %s is %s",
                        member.member().name(),
                        owner.name(),
                        model
                            .getMemberVisibility(symbol) == Visibility.PROTECTED
                                ? "protected"
                                : "private"
                    );
                }
                model.setReference(member.member(), symbol);
                model.setExpressionType(member.member(), symbol.type());
                if (
                    target instanceof ConstType
                        && symbol instanceof FunctionSymbol function
                ) {
                    analyzer.recordConstMethodUse(member, function);
                }
                yield symbol.type();
            }
            case NewExpression creation -> {
                if (creation.className().name().equals("PromiseSource")) {
                    if (creation.typeArguments().size() > 1) {
                        throw new SemanticException(
                            creation.range(),
                            "PromiseSource expects zero or one type argument, found %s",
                            creation.typeArguments().size()
                        );
                    }
                    if (!creation.arguments().isEmpty()) {
                        throw new SemanticException(
                            creation.range(),
                            "PromiseSource expects no constructor arguments"
                        );
                    }
                    final Type valueType =
                        creation.typeArguments().isEmpty()
                            ? BuiltinType.VOID
                            : analyzer.resolveType(
                                creation.typeArguments().getFirst(),
                                context
                            );
                    yield new PromiseSourceType(valueType);
                }
                if (
                    JavaTypes.findClass(creation.className().name()) != null
                        && context.scope()
                            .resolve(creation.className().name()) == null
                ) {
                    final InterfaceType javaType =
                        (InterfaceType) analyzer.resolveNamedType(
                            new NamedTypeNode(
                                creation.className().name(),
                                creation.typeArguments(),
                                creation.range()
                            ),
                            context
                        );
                    final Class<?> javaClass =
                        Objects.requireNonNull(javaType.javaClass());
                    if (javaClass.isInterface()) {
                        throw new SemanticException(
                            creation.range(),
                            "'new' requires a concrete Java collection class"
                        );
                    }
                    final List<java.lang.reflect.Constructor<?>> candidates =
                        new ArrayList<>();
                    for (final var constructor : javaClass.getConstructors()) {
                        if (
                            constructor.getParameterCount() != creation
                                .arguments()
                                .size()
                        ) {
                            continue;
                        }
                        try {
                            for (final var parameter : constructor
                                .getGenericParameterTypes()) {
                                JavaTypes.resolve(parameter, javaType);
                            }
                            candidates.add(constructor);
                        }
                        catch (IllegalArgumentException ignored) {
                            // This constructor belongs to an API outside the exposed aliases.
                        }
                    }
                    if (
                        candidates.size() > 1
                            && creation.arguments().size() == 1
                    ) {
                        final Expression argument =
                            unwrap(creation.arguments().getFirst());
                        if (
                            argument instanceof LambdaExpression
                                || argument instanceof ObjectExpression
                        ) {
                            candidates.removeIf(
                                constructor -> constructor
                                    .getParameterTypes()[0] != java.util.Comparator.class
                            );
                        }
                        else {
                            final Type actual =
                                analyzeExpression(argument, context);
                            candidates.removeIf(
                                constructor -> !analyzer.canAssignJavaArgument(
                                    actual,
                                    JavaTypes.resolve(
                                        constructor
                                            .getGenericParameterTypes()[0],
                                        javaType
                                    ),
                                    argument
                                )
                            );
                        }
                        final var applicable = List.copyOf(candidates);
                        candidates.removeIf(
                            constructor -> applicable.stream()
                                .anyMatch(
                                    other -> analyzer
                                        .isMoreSpecificJavaParameter(
                                            JavaTypes.resolve(
                                                other
                                                    .getGenericParameterTypes()[0],
                                                javaType
                                            ),
                                            JavaTypes.resolve(
                                                constructor
                                                    .getGenericParameterTypes()[0],
                                                javaType
                                            )
                                        )
                                )
                        );
                    }
                    if (candidates.size() != 1) {
                        throw new SemanticException(
                            creation.range(),
                            "No unique supported constructor for %s with %s arguments",
                            javaType,
                            creation.arguments().size()
                        );
                    }
                    final var constructor = candidates.getFirst();
                    for (int i = 0; i < creation.arguments().size(); i++) {
                        final Expression argument = creation.arguments().get(i);
                        final Type wanted =
                            JavaTypes.resolve(
                                constructor.getGenericParameterTypes()[i],
                                javaType
                            );
                        final Type actual =
                            analyzeExpression(argument, context, wanted);
                        if (
                            analyzer.resolveAssignType(
                                actual,
                                wanted,
                                argument
                            ) == null
                        ) {
                            throw new SemanticException(
                                argument.range(),
                                "Cannot pass %s as %s",
                                actual,
                                wanted
                            );
                        }
                    }
                    model.setReference(
                        creation.className(),
                        new JavaClassSymbol(javaType, creation.range())
                    );
                    model.setExpressionType(creation.className(), javaType);
                    model.setJavaConstructor(creation, constructor);
                    yield javaType;
                }
                if (!creation.typeArguments().isEmpty()) {
                    throw new SemanticException(
                        creation.range(),
                        "Type %s does not accept type arguments",
                        creation.className().name()
                    );
                }
                final Type classType =
                    operations.analyzeIdentifierExpression(
                        creation.className(),
                        context
                    );
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
                if (
                    !analyzer.canAccess(
                        (ClassType) classType,
                        model.getConstructorVisibility((ClassType) classType)
                    )
                ) {
                    throw new SemanticException(
                        creation.className().range(),
                        "Constructor of class %s is %s",
                        creation.className().name(),
                        model.getConstructorVisibility(
                            (ClassType) classType
                        ) == Visibility.PROTECTED ? "protected" : "private"
                    );
                }
                analyzeConstructorArguments(
                    (ClassType) classType,
                    creation,
                    creation.arguments(),
                    creation.range(),
                    context
                );
                yield classType;
            }
            case ThisExpression self -> {
                if (analyzer.isAnalyzingSuperArguments()) {
                    throw new SemanticException(
                        self.range(),
                        "Super constructor arguments cannot access 'this' before base initialization"
                    );
                }
                if (analyzer.isAnalyzingConstructorDefault()) {
                    throw new SemanticException(
                        self.range(),
                        "Constructor defaults cannot access 'this' before initialization"
                    );
                }
                if (analyzer.currentInstance() == null) {
                    throw new SemanticException(
                        self.range(),
                        "'this' is only available in instance methods and constructors"
                    );
                }
                yield analyzer.currentInstance();
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
            case LiteralExpression literal ->
                operations.analyzeLiteralExpression(literal);
            case IdentifierExpression identifier ->
                operations.analyzeIdentifierExpression(identifier, context);
            case TupleExpression tuple -> new TupleType(
                tuple.elements()
                    .stream()
                    .map(element -> analyzeExpression(element, context))
                    .toList()
            );
            case ArraySpread spread -> {
                final Type source =
                    analyzeExpression(spread.expression(), context);
                if (
                    !(ConstType.unwrap(source) instanceof ArrayType sourceArray)
                ) {
                    throw new SemanticException(
                        spread.range(),
                        "Array spread requires an Eld array, found %s",
                        source
                    );
                }
                yield sourceArray.elementType();
            }
            case ArrayExpression array ->
                operations.analyzeArrayExpression(array, context);
            case MapExpression map ->
                operations.analyzeMapExpression(map, context);
            case BinaryExpression binary ->
                operations.analyzeBinaryExpression(binary, context);
            case UnaryExpression unary ->
                operations.analyzeUnaryExpression(unary, context);
            case PostfixExpression postfix ->
                operations.analyzePostfixExpression(postfix, context);
            case NamedArgumentExpression named -> throw new SemanticException(
                named.range(),
                "Named arguments are only valid in function calls"
            );
            case CallExpression call -> analyzeCallExpression(call, context);
            case GroupingExpression grouping ->
                analyzeExpression(grouping.expression(), context);
            case SubscriptExpression index ->
                operations.analyzeIndexExpression(index, context);
            case SliceExpression slice ->
                operations.analyzeSliceExpression(slice, context);
            case AssignmentExpression assignment ->
                operations.analyzeAssignmentExpression(assignment, context);
            case TernaryExpression ternary ->
                analyzer.analyzeTernaryExpression(ternary, context);
            case IfExpression conditional ->
                analyzer.analyzeIfExpression(conditional, context);
            case SwitchExpression selection ->
                analyzer.analyzeSwitchExpression(selection, context, true);
            case ObjectExpression object ->
                analyzeExpression(object, context, null);
            case LambdaExpression lambda ->
                analyzeLambdaExpression(lambda, context, null);
        };

        model.setExpressionType(expression, type);

        return type;
    }

    static boolean mutatesCollection(
        final InterfaceType target,
        final Method method
    ) {
        final Class<?> javaClass = Objects.requireNonNull(target.javaClass());
        return (Collection.class.isAssignableFrom(javaClass)
            || Map.class.isAssignableFrom(javaClass))
            && MUTATING_COLLECTION_METHODS.contains(method.getName());
    }

    private static FunctionType collectionMethodType(
        final Type target,
        final JavaMethodSymbol method
    ) {
        final FunctionType type = method.type();
        final Type returned = type.returnType();
        if (
            target instanceof ConstType
                && COLLECTION_VIEW_METHODS.contains(method.name())
                && ConstType.unwrap(returned) instanceof InterfaceType view
                && view.javaClass() != null
                && (Collection.class.isAssignableFrom(view.javaClass())
                    || Map.class.isAssignableFrom(view.javaClass()))
        ) {
            return new FunctionType(
                type.parameterTypes(),
                returned instanceof ConstType
                    ? returned
                    : new ConstType(returned)
            );
        }
        return type;
    }

    public void analyzeConstructorArguments(
        final ClassType classType,
        final List<Expression> arguments,
        final Range range,
        final SemanticContext context
    ) {
        final FunctionType constructor = model.getConstructor(classType);
        if (arguments.size() > constructor.parameterTypes().size()) {
            throw new SemanticException(
                range,
                "Expected %s constructor arguments, found %s",
                constructor.parameterTypes().size(),
                arguments.size()
            );
        }
        for (int i = 0; i < arguments.size(); i++) {
            final Expression argument = arguments.get(i);
            final Type expectedType = constructor.parameterTypes().get(i);
            final Type actual =
                analyzeExpression(argument, context, expectedType);
            if (
                analyzer
                    .resolveAssignType(actual, expectedType, argument) == null
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
            model.getConstructorParameters(classType);
        for (int i = arguments.size(); i < parameters.size(); i++) {
            if (!parameters.get(i).omittable()) {
                throw new SemanticException(
                    range,
                    "Missing required constructor argument: %s",
                    parameters.get(i).name().name()
                );
            }
        }
    }

    private void analyzeConstructorArguments(
        final ClassType classType,
        final NewExpression creation,
        final List<Expression> arguments,
        final Range range,
        final SemanticContext context
    ) {
        final FunctionType constructor = model.getConstructor(classType);
        final List<FunctionParameter> declarations =
            model.getConstructorParameters(classType);
        if (arguments.size() > constructor.parameterTypes().size()) {
            throw new SemanticException(
                range,
                "Expected %s constructor arguments, found %s",
                constructor.parameterTypes().size(),
                arguments.size()
            );
        }
        final List<String> names =
            declarations.stream()
                .map(parameter -> parameter.name().name())
                .toList();
        final List<Integer> parameters = new ArrayList<>();
        final boolean[] assigned =
            new boolean[constructor.parameterTypes().size()];
        boolean seenNamed = false;
        for (int i = 0; i < arguments.size(); i++) {
            final Expression supplied = arguments.get(i);
            final Expression argument;
            final int parameter;
            if (supplied instanceof NamedArgumentExpression named) {
                seenNamed = true;
                parameter = names.indexOf(named.name().name());
                if (parameter < 0) {
                    throw new SemanticException(
                        named.name().range(),
                        "Unknown constructor parameter: %s",
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
                    "Argument supplied more than once for constructor parameter: %s",
                    names.get(parameter)
                );
            }
            assigned[parameter] = true;
            parameters.add(parameter);
            if (supplied instanceof NamedArgumentExpression named) {
                model.setNamedArgument(
                    named.name(),
                    declarations.get(parameter).name()
                );
            }
            final Type expected = constructor.parameterTypes().get(parameter);
            final Type actual = analyzeExpression(argument, context, expected);
            if (
                analyzer.resolveAssignType(actual, expected, argument) == null
            ) {
                throw new SemanticException(
                    argument.range(),
                    "Cannot assign %s to %s",
                    actual,
                    expected
                );
            }
        }
        for (int i = 0; i < assigned.length; i++) {
            if (!assigned[i] && !declarations.get(i).omittable()) {
                throw new SemanticException(
                    range,
                    "Missing required constructor argument: %s",
                    declarations.get(i).name().name()
                );
            }
        }
        model.setConstructorArgumentParameters(creation, parameters);
    }

    public Type analyzeLambdaExpression(
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
        model.setLambdaFunction(lambda, symbol);
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
            if (
                analyzer
                    .resolveAssignType(actual, returnType, expression) == null
            ) {
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
                analyzer.setLambdaReturns(symbol, returns);
            }
            try {
                analyzer.analyzeBlockStatement(
                    (BlockStatement) lambda.body(),
                    lambdaContext
                );
            }
            finally {
                analyzer.clearLambdaReturns(symbol);
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
                        : analyzer.commonBranchType(inferred, type, statement);
            }
            returnType =
                target != null
                    ? target.returnType()
                    : Objects.requireNonNull(inferred);
            for (final ReturnStatement statement : returns) {
                if (statement.value() != null) {
                    analyzer.resolveAssignType(
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
        final Method promiseMethod = promiseMethod(call, context);
        if (promiseMethod != null) {
            model.setPromiseMethod(call, promiseMethod);
            return analyzePromiseCall(call, context, promiseMethod);
        }
        final boolean previousCallee = analyzingCallee;
        final int previousArity = callArity;
        final List<Expression> previousArguments = javaCallArguments;
        final Type type;
        analyzingCallee = true;
        callArity = call.arguments().size();
        javaCallArguments = call.arguments();
        try {
            type = analyzeExpression(call.callee(), context);
        }
        finally {
            analyzingCallee = previousCallee;
            callArity = previousArity;
            javaCallArguments = previousArguments;
        }
        if (type == BuiltinFunctionType.ARRAY_SORT) {
            if (!call.arguments().isEmpty()) {
                throw new SemanticException(
                    call.range(),
                    "Array sorting expects no arguments"
                );
            }
            return BuiltinType.VOID;
        }
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
        if (
            type == BuiltinFunctionType.DIR || type == BuiltinFunctionType.HELP
        ) {
            final String name =
                type == BuiltinFunctionType.DIR ? "Dir" : "Help";
            if (call.arguments().size() != 1) {
                throw new SemanticException(
                    call.range(),
                    "%s expects one argument, found %s",
                    name,
                    call.arguments().size()
                );
            }
            final Expression argument = call.arguments().getFirst();
            if (analyzeExpression(argument, context) == BuiltinType.VOID) {
                throw new SemanticException(
                    argument.range(),
                    "A %s argument must produce a value",
                    name.toLowerCase(Locale.ROOT)
                );
            }
            return type == BuiltinFunctionType.DIR
                ? new ArrayType(BuiltinType.STRING)
                : BuiltinType.VOID;
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
            if (
                analyzer.resolveAssignType(actual, expected, argument) == null
            ) {
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

    private @Nullable Method promiseMethod(
        final CallExpression call,
        final SemanticContext context
    ) {
        final Expression callee = unwrap(call.callee());
        if (!(callee instanceof MemberExpression member)) {
            return null;
        }
        final Expression target = unwrap(member.target());
        if (
            !(target instanceof IdentifierExpression identifier)
                || !identifier.name().equals("Promise")
                || context.scope().resolve(identifier.name()) != null
        ) {
            return null;
        }
        final Method method =
            eldApiMethod(EldPromise.class, member.member().name(), true);
        if (method != null) {
            return method;
        }
        throw new SemanticException(
            member.member().range(),
            "Unknown static Promise method '%s'",
            member.member().name()
        );
    }

    private static @Nullable Method eldApiMethod(
        final Class<?> owner,
        final String name,
        final boolean staticMethod
    ) {
        for (final Method method : owner.getDeclaredMethods()) {
            if (
                Modifier.isStatic(method.getModifiers()) == staticMethod
                    && method.isAnnotationPresent(EldApi.class)
                    && method.getName().equals(name)
            ) {
                return method;
            }
        }
        return null;
    }

    private Type analyzePromiseCall(
        final CallExpression call,
        final SemanticContext context,
        final Method method
    ) {
        return switch (method.getName()) {
            case "resolve" -> analyzePromiseResolve(call, context);
            case "reject" -> analyzePromiseReject(call, context);
            case "all" -> analyzePromiseAll(call, context);
            default -> throw new IllegalStateException(
                "Unsupported Promise API method: " + method
            );
        };
    }

    private Type analyzePromiseResolve(
        final CallExpression call,
        final SemanticContext context
    ) {
        if (call.arguments().size() > 1) {
            throw new SemanticException(
                call.range(),
                "Promise.resolve expects zero or one argument, found %s",
                call.arguments().size()
            );
        }
        final Type valueType;
        if (call.arguments().isEmpty()) {
            valueType = BuiltinType.VOID;
        }
        else {
            final Expression value = call.arguments().getFirst();
            valueType = analyzeExpression(value, context);
            if (valueType == BuiltinType.VOID) {
                throw new SemanticException(
                    value.range(),
                    "Promise.resolve argument must produce a value"
                );
            }
        }
        return valueType instanceof PromiseType promise
            ? promise
            : new PromiseType(valueType);
    }

    private Type analyzePromiseReject(
        final CallExpression call,
        final SemanticContext context
    ) {
        if (call.arguments().size() != 1) {
            throw new SemanticException(
                call.range(),
                "Promise.reject expects one argument, found %s",
                call.arguments().size()
            );
        }
        final Expression error = call.arguments().getFirst();
        final Type errorType = analyzeExpression(error, context);
        SemanticAnalyzer.requireThrowable(errorType, error.range());
        return new PromiseType(BuiltinType.ANY);
    }

    private Type analyzePromiseAll(
        final CallExpression call,
        final SemanticContext context
    ) {
        if (call.arguments().size() != 1) {
            throw new SemanticException(
                call.range(),
                "Promise.all expects one argument, found %s",
                call.arguments().size()
            );
        }
        final Expression values = call.arguments().getFirst();
        final Type valuesType = analyzeExpression(values, context);
        if (!(ConstType.unwrap(valuesType) instanceof ArrayType array)) {
            throw new SemanticException(
                values.range(),
                "Promise.all expects an array, found %s",
                valuesType
            );
        }
        final Type valueType;
        if (
            array.elementType() == BuiltinType.NULL
                && values instanceof ArrayExpression literal
                && literal.elements().isEmpty()
        ) {
            valueType = BuiltinType.ANY;
        }
        else if (array.elementType() instanceof PromiseType promise) {
            valueType = promise.valueType();
        }
        else {
            throw new SemanticException(
                values.range(),
                "Promise.all expects [Promise<T>], found %s",
                valuesType
            );
        }
        if (valueType == BuiltinType.VOID) {
            throw new SemanticException(
                values.range(),
                "Promise.all cannot collect Promise<void> values"
            );
        }
        return new PromiseType(new ArrayType(valueType));
    }

    public static Expression unwrap(final Expression expression) {
        return SemanticAnalyzerExpressionOperations.unwrap(expression);
    }

    public Type analyzeIdentifierExpression(
        final IdentifierExpression identifier,
        final SemanticContext context
    ) {
        return operations.analyzeIdentifierExpression(identifier, context);
    }

    public static boolean isFloatingLiteral(final Expression expression) {
        return SemanticAnalyzerExpressionOperations
            .isFloatingLiteral(expression);
    }

    public static @Nullable BigInteger integerLiteral(
        final Expression expression
    ) {
        return SemanticAnalyzerExpressionOperations.integerLiteral(expression);
    }
}
