package com.github.andreasarvidsson.eld.semantic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.ObjectMember;
import com.github.andreasarvidsson.eld.parser.ObjectEntry;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.RecordDeclaration;
import java.util.Map;
import java.util.HashMap;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.TypeNode;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;

import java.util.Set;
import java.util.Collections;
import com.github.andreasarvidsson.eld.parser.LambdaExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.NewExpression;
import com.github.andreasarvidsson.eld.parser.Visibility;
import org.jspecify.annotations.Nullable;

public final class SemanticModel {
    private static final String ARROW = " -> ";
    private final IdentityHashMap<IdentifierDeclaration, FunctionParameter> parameterDetails =
        new IdentityHashMap<>();
    private final Map<ClassType, List<FunctionParameter>> constructorParameters =
        new HashMap<>();
    private final IdentityHashMap<ObjectExpression, List<ObjectMember>> objectMembers =
        new IdentityHashMap<>();
    private final IdentityHashMap<ObjectExpression, List<ObjectEntry>> objectEvaluation =
        new IdentityHashMap<>();
    private final Set<ObjectMember> spreadMethods =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<MemberExpression, Type> memberOwners =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> expressionTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> conversionTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> unionMemberTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<TypeNode, Type> resolvedTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<AstNode, Symbol> declarations =
        new IdentityHashMap<>();
    private final IdentityHashMap<IdentifierExpression, Symbol> references =
        new IdentityHashMap<>();
    private final IdentityHashMap<FunctionSymbol, List<IdentifierDeclaration>> functionParameters =
        new IdentityHashMap<>();
    private final IdentityHashMap<FunctionSymbol, FunctionDeclaration> functionDeclarations =
        new IdentityHashMap<>();
    private final IdentityHashMap<VariableSymbol, FunctionSymbol> variableOwners =
        new IdentityHashMap<>();
    private final IdentityHashMap<VariableSymbol, ClassType> constructorVariableOwners =
        new IdentityHashMap<>();
    private final IdentityHashMap<VariableSymbol, Expression> variableInitializers =
        new IdentityHashMap<>();
    private final Set<VariableSymbol> constantCallableVariables =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<LambdaExpression, FunctionSymbol> lambdaFunctions =
        new IdentityHashMap<>();
    private final Map<ClassType, ClassDeclaration> classDeclarations =
        new HashMap<>();
    private final IdentityHashMap<IdentifierDeclaration, IdentifierDeclaration> namedArguments =
        new IdentityHashMap<>();
    private final IdentityHashMap<CallExpression, List<Integer>> argumentParameters =
        new IdentityHashMap<>();
    private final IdentityHashMap<CallExpression, Method> promiseMethods =
        new IdentityHashMap<>();
    private final IdentityHashMap<NewExpression, List<Integer>> constructorArgumentParameters =
        new IdentityHashMap<>();
    private final Map<ClassType, FunctionType> constructors = new HashMap<>();
    private final Map<ClassType, Visibility> constructorVisibility =
        new HashMap<>();
    private final Map<ClassType, Map<String, VariableSymbol>> interfaceFields =
        new HashMap<>();
    private final Map<InterfaceType, InterfaceContract> interfaces =
        new java.util.LinkedHashMap<>();
    private final Map<ClassType, List<InterfaceType>> implementedInterfaces =
        new HashMap<>();
    private final IdentityHashMap<Symbol, ClassType> classMemberOwners =
        new IdentityHashMap<>();
    private final IdentityHashMap<NewExpression, Constructor<?>> javaConstructors =
        new IdentityHashMap<>();
    private final IdentityHashMap<RecordDeclaration, ClassDeclaration> recordClasses =
        new IdentityHashMap<>();
    private final IdentityHashMap<ClassDeclaration, RecordDeclaration> recordDeclarations =
        new IdentityHashMap<>();
    private final Set<ClassDeclaration> loweredRecordClasses =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<FunctionSymbol, Type> asyncResultTypes =
        new IdentityHashMap<>();

    public void setAsyncResultType(
        final FunctionSymbol function,
        final Type resultType
    ) {
        asyncResultTypes.put(function, resultType);
    }

    public @Nullable Type getAsyncResultType(final FunctionSymbol function) {
        return asyncResultTypes.get(function);
    }

    public boolean isAsync(final FunctionSymbol function) {
        return asyncResultTypes.containsKey(function);
    }

    public void setFunctionDeclaration(
        final FunctionSymbol function,
        final FunctionDeclaration declaration
    ) {
        functionDeclarations.put(function, declaration);
    }

    public @Nullable FunctionDeclaration getFunctionDeclaration(
        final FunctionSymbol function
    ) {
        return functionDeclarations.get(function);
    }

    public Map<FunctionSymbol, FunctionDeclaration> getFunctionDeclarations() {
        return Collections.unmodifiableMap(functionDeclarations);
    }

    public void setVariableOwner(
        final VariableSymbol variable,
        final @Nullable FunctionSymbol function
    ) {
        if (function != null) {
            variableOwners.put(variable, function);
        }
    }

    public @Nullable FunctionSymbol findVariableOwner(
        final VariableSymbol variable
    ) {
        return variableOwners.get(variable);
    }

    public void setConstructorVariableOwner(
        final VariableSymbol variable,
        final ClassType owner
    ) {
        constructorVariableOwners.put(variable, owner);
    }

    public @Nullable ClassType findConstructorVariableOwner(
        final VariableSymbol variable
    ) {
        return constructorVariableOwners.get(variable);
    }

    public void setVariableInitializer(
        final VariableSymbol variable,
        final Expression initializer
    ) {
        variableInitializers.put(variable, initializer);
    }

    public @Nullable Expression findVariableInitializer(
        final VariableSymbol variable
    ) {
        return variableInitializers.get(variable);
    }

    public Map<VariableSymbol, Expression> getVariableInitializers() {
        return Collections.unmodifiableMap(variableInitializers);
    }

    public void setConstantCallable(final VariableSymbol variable) {
        constantCallableVariables.add(variable);
    }

    public boolean isConstantCallable(final VariableSymbol variable) {
        return constantCallableVariables.contains(variable);
    }

    public void setClassDeclaration(
        final ClassType type,
        final ClassDeclaration declaration
    ) {
        classDeclarations.put(type, declaration);
    }

    public @Nullable ClassDeclaration findClassDeclaration(
        final ClassType type
    ) {
        return classDeclarations.get(type);
    }

    public void setLambdaFunction(
        final LambdaExpression lambda,
        final FunctionSymbol function
    ) {
        lambdaFunctions.put(lambda, function);
    }

    public FunctionSymbol getLambdaFunction(final LambdaExpression lambda) {
        return Objects.requireNonNull(lambdaFunctions.get(lambda));
    }

    public void setPromiseMethod(
        final CallExpression call,
        final Method method
    ) {
        promiseMethods.put(call, method);
    }

    public @Nullable Method getPromiseMethod(final CallExpression call) {
        return promiseMethods.get(call);
    }

    public void setRecordClass(
        final RecordDeclaration record,
        final ClassDeclaration declaration
    ) {
        recordClasses.put(record, declaration);
        recordDeclarations.put(declaration, record);
        loweredRecordClasses.add(declaration);
    }

    public ClassDeclaration getRecordClass(final RecordDeclaration record) {
        return Objects.requireNonNull(recordClasses.get(record));
    }

    public boolean isRecordClass(final ClassDeclaration declaration) {
        return loweredRecordClasses.contains(declaration);
    }

    public RecordDeclaration getRecordDeclaration(
        final ClassDeclaration declaration
    ) {
        return Objects.requireNonNull(recordDeclarations.get(declaration));
    }

    public boolean isRecordClass(final ClassType type) {
        return loweredRecordClasses.stream().anyMatch(declaration -> {
            final Symbol symbol = declarations.get(declaration.name());
            return symbol != null && symbol.type().equals(type);
        });
    }

    public void setObjectMembers(
        final ObjectExpression object,
        final List<ObjectMember> members,
        final List<ObjectEntry> evaluation
    ) {
        objectMembers.put(object, List.copyOf(members));
        objectEvaluation.put(object, List.copyOf(evaluation));
    }

    public List<ObjectMember> getObjectMembers(final ObjectExpression object) {
        return Objects.requireNonNull(objectMembers.get(object));
    }

    public List<ObjectEntry> getObjectEvaluation(
        final ObjectExpression object
    ) {
        return Objects.requireNonNull(objectEvaluation.get(object));
    }

    public void setSpreadMethod(final ObjectMember member) {
        spreadMethods.add(member);
    }

    public boolean isSpreadMethod(final ObjectMember member) {
        return spreadMethods.contains(member);
    }

    public void setInterfaceFields(
        final ClassType type,
        final Map<String, VariableSymbol> fields
    ) {
        interfaceFields.put(type, fields);
    }

    public Map<String, VariableSymbol> getInterfaceFields(
        final ClassType type
    ) {
        return interfaceFields.getOrDefault(type, Map.of());
    }

    public void setInterface(
        final InterfaceType type,
        final InterfaceContract contract
    ) {
        interfaces.put(type, contract);
    }

    public InterfaceContract getInterface(final InterfaceType type) {
        if (type.javaClass() != null) {
            return JavaTypes.contract(type);
        }
        return Objects.requireNonNull(interfaces.get(type));
    }

    public Set<InterfaceType> getInterfaceTypes() {
        return Collections.unmodifiableSet(interfaces.keySet());
    }

    public void setImplementedInterfaces(
        final ClassType type,
        final List<InterfaceType> contracts
    ) {
        implementedInterfaces.put(type, List.copyOf(contracts));
    }

    public List<InterfaceType> getImplementedInterfaces(final ClassType type) {
        return implementedInterfaces.getOrDefault(type, List.of());
    }

    public void setClassMemberOwner(
        final Symbol symbol,
        final ClassType owner
    ) {
        classMemberOwners.put(symbol, owner);
    }

    public ClassType getClassMemberOwner(final Symbol symbol) {
        return Objects.requireNonNull(classMemberOwners.get(symbol));
    }

    public @Nullable ClassType findClassMemberOwner(final Symbol symbol) {
        return classMemberOwners.get(symbol);
    }

    public @Nullable RecordDeclaration findRecordDeclaration(
        final ClassType type
    ) {
        for (final var entry : recordDeclarations.entrySet()) {
            final Symbol symbol = declarations.get(entry.getKey().name());
            if (symbol != null && symbol.type().equals(type)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public boolean isSubtype(final Type source, final Type target) {
        if (source.equals(target)) {
            return true;
        }
        if (target instanceof ConstType constant) {
            return isSubtype(ConstType.unwrap(source), constant.type());
        }
        if (source instanceof ConstType) {
            return false;
        }
        if (
            source instanceof ClassType type && target instanceof ClassType base
        ) {
            return isSubclassOf(type, base);
        }
        if (target instanceof InterfaceType contract) {
            if (
                contract.javaClass() == Comparable.class
                    && JavaTypes.hasNaturalOrder(source)
            ) {
                return contract.typeArguments().equals(List.of(source));
            }
            if (source instanceof InterfaceType type) {
                if (type.javaClass() != null && contract.javaClass() != null) {
                    return contract.javaClass()
                        .isAssignableFrom(type.javaClass())
                        && type.typeArguments()
                            .equals(contract.typeArguments());
                }
                return getInterface(type).superInterfaces()
                    .stream()
                    .anyMatch(parent -> isSubtype(parent, contract));
            }
            if (source instanceof ClassType type) {
                for (ClassType current = type; current != null; current =
                    getSuperclass(current)) {
                    for (final InterfaceType implemented : getImplementedInterfaces(
                        current
                    )) {
                        if (isSubtype(implemented, contract)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    public boolean hasNaturalOrder(final Type type) {
        final Type unqualified = ConstType.unwrap(type);
        return JavaTypes.hasNaturalOrder(unqualified)
            || hasNaturalOrder(unqualified, unqualified);
    }

    private boolean hasNaturalOrder(final Type type, final Type element) {
        if (type instanceof InterfaceType contract) {
            if (contract.javaClass() == Comparable.class) {
                final Type argument = contract.typeArguments().getFirst();
                return argument == BuiltinType.ANY
                    || isSubtype(element, argument);
            }
            return getInterface(contract).superInterfaces()
                .stream()
                .anyMatch(parent -> hasNaturalOrder(parent, element));
        }
        if (type instanceof ClassType cls) {
            for (ClassType current = cls; current != null; current =
                getSuperclass(current)) {
                for (final InterfaceType contract : getImplementedInterfaces(
                    current
                )) {
                    if (hasNaturalOrder(contract, element)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void setJavaConstructor(
        final NewExpression expression,
        final Constructor<?> constructor
    ) {
        javaConstructors.put(expression, constructor);
    }

    public Constructor<?> getJavaConstructor(final NewExpression expression) {
        return Objects.requireNonNull(javaConstructors.get(expression));
    }

    private final Set<LambdaExpression> receiverlessLambdas =
        Collections.newSetFromMap(new IdentityHashMap<>());

    public void setReceiverlessLambda(final LambdaExpression lambda) {
        receiverlessLambdas.add(lambda);
    }

    public boolean isReceiverlessLambda(final LambdaExpression lambda) {
        return receiverlessLambdas.contains(lambda);
    }

    private final Map<ClassType, ClassType> superclasses = new HashMap<>();

    public void setSuperclass(
        final ClassType type,
        final ClassType superclass
    ) {
        superclasses.put(type, superclass);
    }

    public @Nullable ClassType getSuperclass(final ClassType type) {
        return superclasses.get(type);
    }

    public Set<ClassType> getClassTypes() {
        return Collections.unmodifiableSet(constructors.keySet());
    }

    public boolean isSubclassOf(final ClassType type, final ClassType base) {
        for (ClassType current = type; current != null; current =
            getSuperclass(current)) {
            if (current.equals(base)) {
                return true;
            }
        }
        return false;
    }

    public @Nullable ClassType commonClassType(
        final ClassType left,
        final ClassType right
    ) {
        for (ClassType current = left; current != null; current =
            getSuperclass(current)) {
            if (isSubclassOf(right, current)) {
                return current;
            }
        }
        return null;
    }

    private final IdentityHashMap<Symbol, Visibility> memberVisibility =
        new IdentityHashMap<>();

    public void setMemberVisibility(
        final Symbol symbol,
        final Visibility visibility
    ) {
        memberVisibility.put(symbol, visibility);
    }

    public Visibility getMemberVisibility(final Symbol symbol) {
        return memberVisibility.getOrDefault(symbol, Visibility.PRIVATE);
    }

    public void setConstructorVisibility(
        final ClassType owner,
        final Visibility visibility
    ) {
        constructorVisibility.put(owner, visibility);
    }

    public Visibility getConstructorVisibility(final ClassType owner) {
        return constructorVisibility.getOrDefault(owner, Visibility.PRIVATE);
    }

    private final IdentityHashMap<LambdaExpression, List<Symbol>> lambdaCaptures =
        new IdentityHashMap<>();
    private final Set<Symbol> capturedMutable =
        Collections.newSetFromMap(new IdentityHashMap<>());

    public void setLambdaCaptures(
        final LambdaExpression lambda,
        final List<Symbol> captures
    ) {
        lambdaCaptures.put(lambda, captures);
        for (final Symbol symbol : captures) {
            if (
                symbol instanceof VariableSymbol variable
                    && variable.mutability() == Mutability.VAR
            ) {
                capturedMutable.add(symbol);
            }
        }
    }

    public List<Symbol> getLambdaCaptures(final LambdaExpression lambda) {
        return Objects.requireNonNull(lambdaCaptures.get(lambda));
    }

    public boolean isCapturedMutable(final Symbol symbol) {
        return capturedMutable.contains(symbol);
    }

    public @Nullable Symbol findDeclaredSymbol(final AstNode node) {
        return declarations.get(node);
    }

    public void setParameterDetails(final FunctionParameter parameter) {
        parameterDetails.put(parameter.name(), parameter);
    }

    public FunctionParameter getParameterDetails(
        final IdentifierDeclaration name
    ) {
        return Objects.requireNonNull(parameterDetails.get(name));
    }

    public void setConstructorParameters(
        final ClassType owner,
        final List<FunctionParameter> parameters
    ) {
        constructorParameters.put(owner, List.copyOf(parameters));
    }

    public List<FunctionParameter> getConstructorParameters(
        final ClassType owner
    ) {
        return constructorParameters.getOrDefault(owner, List.of());
    }

    public void setConstructor(final ClassType owner, final FunctionType type) {
        constructors.put(owner, type);
    }

    public FunctionType getConstructor(final ClassType owner) {
        return Objects.requireNonNull(constructors.get(owner));
    }

    public void setConstructorSymbol(
        final ConstructorDeclaration declaration,
        final ConstructorSymbol symbol
    ) {
        declarations.put(declaration, symbol);
    }

    public void setMemberOwner(
        final MemberExpression expression,
        final Type owner
    ) {
        memberOwners.put(expression, owner);
    }

    public Type getMemberOwner(final MemberExpression expression) {
        return Objects.requireNonNull(memberOwners.get(expression));
    }

    public void setExpressionType(
        final Expression expression,
        final Type type
    ) {
        expressionTypes.put(expression, type);
    }

    public Type getExpressionType(final Expression expression) {
        return Objects.requireNonNull(expressionTypes.get(expression));
    }

    public void setConversionType(
        final Expression expression,
        final Type type
    ) {
        conversionTypes.put(expression, type);
    }

    public Type getConversionType(final Expression expression) {
        return Objects.requireNonNull(conversionTypes.get(expression));
    }

    public Type getEffectiveType(final Expression expression) {
        return conversionTypes
            .getOrDefault(expression, getExpressionType(expression));
    }

    public void setResolvedType(final TypeNode typeNode, final Type type) {
        resolvedTypes.put(typeNode, type);
    }

    public void setUnionConversion(
        final Expression expression,
        final Type member,
        final UnionType union
    ) {
        unionMemberTypes.put(expression, member);
        setConversionType(expression, union);
    }

    public Type getUnionMemberType(final Expression expression) {
        return unionMemberTypes
            .getOrDefault(expression, getExpressionType(expression));
    }

    public Type getResolvedType(final TypeNode typeNode) {
        return Objects.requireNonNull(resolvedTypes.get(typeNode));
    }

    public void setSymbol(
        final IdentifierDeclaration declaration,
        final Symbol symbol
    ) {
        declarations.put(declaration, symbol);
    }

    public Symbol getSymbol(final IdentifierDeclaration declaration) {
        return Objects.requireNonNull(declarations.get(declaration));
    }

    public void setReference(
        final IdentifierExpression expression,
        final Symbol symbol
    ) {
        references.put(expression, symbol);
    }

    public Symbol getReference(final IdentifierExpression expression) {
        return Objects.requireNonNull(references.get(expression));
    }

    public @Nullable Symbol findReference(
        final IdentifierExpression expression
    ) {
        return references.get(expression);
    }

    public void setFunctionParameters(
        final FunctionSymbol function,
        final List<IdentifierDeclaration> parameters
    ) {
        functionParameters.put(function, List.copyOf(parameters));
    }

    public List<IdentifierDeclaration> getFunctionParameters(
        final FunctionSymbol function
    ) {
        return Objects.requireNonNull(functionParameters.get(function));
    }

    public void setNamedArgument(
        final IdentifierDeclaration argument,
        final IdentifierDeclaration parameter
    ) {
        namedArguments.put(argument, parameter);
    }

    public void setArgumentParameters(
        final CallExpression call,
        final List<Integer> parameters
    ) {
        argumentParameters.put(call, List.copyOf(parameters));
    }

    public List<Integer> getArgumentParameters(final CallExpression call) {
        return Objects.requireNonNull(argumentParameters.get(call));
    }

    public void setConstructorArgumentParameters(
        final NewExpression creation,
        final List<Integer> parameters
    ) {
        constructorArgumentParameters.put(creation, List.copyOf(parameters));
    }

    public List<Integer> getConstructorArgumentParameters(
        final NewExpression creation
    ) {
        return Objects
            .requireNonNull(constructorArgumentParameters.get(creation));
    }

    @Override
    public String toString() {
        final List<String> lines = new ArrayList<>();
        appendSection(lines, "Expression types:", expressionTypes);
        appendSection(lines, "Resolved types:", resolvedTypes);

        appendSection(
            lines,
            "Conversions:",
            conversionTypes,
            (expression, type) -> expressionTypes.get(expression) + ARROW + type
        );
        appendSection(
            lines,
            "Declarations:",
            declarations,
            (declaration, symbol) -> formatDeclaration(symbol)
        );
        appendSection(
            lines,
            "References:",
            references,
            (expression, symbol) -> symbol instanceof BuiltinFunctionSymbol
                ? "builtin " + symbol.name()
                : symbol.range().toString()
        );
        appendSection(
            lines,
            "Named arguments:",
            namedArguments,
            (argument, parameter) -> "parameter " + parameter.range()
        );
        return Objects.requireNonNull(String.join("\n", lines).stripTrailing());
    }

    private String formatDeclaration(final Symbol symbol) {
        if (symbol instanceof FunctionSymbol function) {
            final FunctionDeclaration declaration =
                functionDeclarations.get(function);
            final String modifiers =
                declaration == null
                    ? ""
                    : declaration.modifiers()
                        .stream()
                        .map(
                            modifier -> modifier.toString()
                                .toLowerCase(Locale.ROOT)
                        )
                        .collect(Collectors.joining(" "));
            final String prefix = modifiers.isEmpty() ? "" : modifiers + " ";
            return prefix + "func " + symbol;
        }
        if (
            symbol instanceof VariableSymbol variable
                && isConstantCallable(variable)
        ) {
            return "%s %s: const %s".formatted(
                variable.mutability().toString().toLowerCase(Locale.ROOT),
                variable.name(),
                variable.type()
            );
        }
        return symbol.toString();
    }

    private static void appendSection(
        final List<String> lines,
        final String heading,
        final IdentityHashMap<? extends AstNode, ?> entries
    ) {
        appendSection(
            lines,
            heading,
            entries,
            (node, value) -> String.valueOf(value)
        );
    }

    private static <K extends AstNode, V> void appendSection(
        final List<String> lines,
        final String heading,
        final IdentityHashMap<K, V> entries,
        final BiFunction<K, V, String> formatValue
    ) {
        if (entries.isEmpty()) {
            return;
        }
        lines.add(heading);
        entries.entrySet()
            .stream()
            .sorted(
                Comparator
                    .comparing(
                        (
                            Map.Entry<K, V> entry
                        ) -> Objects.requireNonNull(entry.getKey()).range()
                    )
                    .thenComparing(
                        entry -> formatValue
                            .apply(entry.getKey(), entry.getValue())
                    )
            )
            .forEach(
                entry -> lines
                    .add(
                        "  " + Objects.requireNonNull(entry.getKey()).range()
                            + ARROW
                            + formatValue
                                .apply(entry.getKey(), entry.getValue())
                    )
            );
        lines.add("");
    }

}
