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
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.ObjectMember;
import com.github.andreasarvidsson.eld.parser.ObjectEntry;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.EnumDeclaration;
import com.github.andreasarvidsson.eld.parser.RecordDeclaration;
import java.util.Map;
import java.util.HashMap;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.MemberExpression;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.TypeNode;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.BindingDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.MemberDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierPattern;
import com.github.andreasarvidsson.eld.parser.RecordPattern;

import java.util.Set;
import java.util.Collections;
import com.github.andreasarvidsson.eld.parser.LambdaExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.NewExpression;
import com.github.andreasarvidsson.eld.parser.SuperConstructorCall;
import com.github.andreasarvidsson.eld.parser.Visibility;
import org.jspecify.annotations.Nullable;

public final class SemanticModel {
    private static final String ARROW = " -> ";
    private final IdentityHashMap<BindingDeclaration, FunctionParameter> parameterDetails =
        new IdentityHashMap<>();
    private final IdentityHashMap<FunctionParameter, Type> declaredParameterTypes =
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
    private final IdentityHashMap<IdentifierExpression, Type> memberReferenceOwners =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> expressionTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> probedExpressionTypes =
        new IdentityHashMap<>();
    private final Set<Expression> switchTypeMatches =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Expression> switchDualMatches =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<IdentifierExpression, Type> narrowedTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<Expression, Type> conversionTypes =
        new IdentityHashMap<>();
    private int typeProbeDepth;
    private final IdentityHashMap<Expression, Type> unionMemberTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<TypeNode, Type> resolvedTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<AstNode, Symbol> declarations =
        new IdentityHashMap<>();
    private final IdentityHashMap<AstNode, MemberDeclaration> memberDeclarations =
        new IdentityHashMap<>();
    private final Set<AstNode> syntheticDeclarations =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<AstNode, Symbol> references =
        new IdentityHashMap<>();
    private final IdentityHashMap<FunctionSymbol, List<BindingDeclaration>> functionParameters =
        new IdentityHashMap<>();
    private final IdentityHashMap<FunctionSymbol, FunctionDeclaration> functionDeclarations =
        new IdentityHashMap<>();
    private final IdentityHashMap<FunctionSymbol, List<FunctionType>> overrideBridges =
        new IdentityHashMap<>();
    private final Map<ClassType, List<InterfaceBridge>> interfaceBridges =
        new HashMap<>();
    private final IdentityHashMap<FunctionSymbol, InterfaceType> interfaceMethodOwners =
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
    private final Map<Type, List<String>> permittedSubclasses = new HashMap<>();
    private final IdentityHashMap<IdentifierDeclaration, IdentifierDeclaration> namedArguments =
        new IdentityHashMap<>();
    private final IdentityHashMap<CallExpression, List<Integer>> argumentParameters =
        new IdentityHashMap<>();
    private final IdentityHashMap<CallExpression, Method> promiseMethods =
        new IdentityHashMap<>();
    private final IdentityHashMap<NewExpression, List<Integer>> constructorArgumentParameters =
        new IdentityHashMap<>();
    private final IdentityHashMap<SuperConstructorCall, List<Integer>> superConstructorArgumentParameters =
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
    private final IdentityHashMap<EnumDeclaration, ClassDeclaration> enumClasses =
        new IdentityHashMap<>();
    private final IdentityHashMap<ClassDeclaration, EnumDeclaration> enumDeclarations =
        new IdentityHashMap<>();
    private final Set<NewExpression> enumConstantCreations =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<NewExpression, Integer> enumOrdinals =
        new IdentityHashMap<>();
    private final IdentityHashMap<NewExpression, String> enumNames =
        new IdentityHashMap<>();
    private final Set<FunctionDeclaration> enumIntrinsics =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<ClassDeclaration, RecordDeclaration> recordDeclarations =
        new IdentityHashMap<>();
    private final Set<ClassDeclaration> loweredRecordClasses =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final IdentityHashMap<FunctionSymbol, Type> asyncResultTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<IdentifierPattern, Symbol> patternSymbols =
        new IdentityHashMap<>();
    private final IdentityHashMap<IdentifierPattern, Type> patternSourceTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<IdentifierPattern, Type> patternConversionTypes =
        new IdentityHashMap<>();
    private final IdentityHashMap<RecordPattern, ClassType> recordPatternTypes =
        new IdentityHashMap<>();

    public void setPatternBinding(
        final IdentifierPattern pattern,
        final Symbol symbol,
        final Type sourceType,
        final Type conversionType
    ) {
        patternSymbols.put(pattern, symbol);
        patternSourceTypes.put(pattern, sourceType);
        patternConversionTypes.put(pattern, conversionType);
    }

    public Symbol getPatternSymbol(final IdentifierPattern pattern) {
        return Objects.requireNonNull(patternSymbols.get(pattern));
    }

    public Type getPatternSourceType(final IdentifierPattern pattern) {
        return Objects.requireNonNull(patternSourceTypes.get(pattern));
    }

    public Type getPatternConversionType(final IdentifierPattern pattern) {
        return Objects.requireNonNull(patternConversionTypes.get(pattern));
    }

    public void setRecordPatternType(
        final RecordPattern pattern,
        final ClassType type
    ) {
        recordPatternTypes.put(pattern, type);
    }

    public ClassType getRecordPatternType(final RecordPattern pattern) {
        return Objects.requireNonNull(recordPatternTypes.get(pattern));
    }

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

    public void setDeclaredParameterType(
        final FunctionParameter parameter,
        final Type type
    ) {
        declaredParameterTypes.put(parameter, type);
    }

    public Type getDeclaredParameterType(final FunctionParameter parameter) {
        return Objects.requireNonNull(declaredParameterTypes.get(parameter));
    }

    public Map<FunctionSymbol, FunctionDeclaration> getFunctionDeclarations() {
        return Collections.unmodifiableMap(functionDeclarations);
    }

    public String formatFunctionSignature(final FunctionSymbol function) {
        final FunctionDeclaration declaration =
            getFunctionDeclaration(function);
        final List<BindingDeclaration> parameters =
            functionParameters.get(function);
        final List<String> parameterTypes =
            declaration != null
                ? declaration.parameters()
                    .stream()
                    .map(this::formatParameterType)
                    .toList()
                : parameters != null
                    ? parameters.stream()
                        .map(this::getParameterDetails)
                        .map(this::formatParameterType)
                        .toList()
                    : function.type()
                        .parameterTypes()
                        .stream()
                        .map(Type::toString)
                        .toList();
        final List<Boolean> omittable =
            declaration != null
                ? declaration.parameters()
                    .stream()
                    .map(FunctionParameter::omittable)
                    .toList()
                : parameters != null
                    ? parameters.stream()
                        .map(this::getParameterDetails)
                        .map(FunctionParameter::omittable)
                        .toList()
                    : Collections.nCopies(parameterTypes.size(), false);
        return function.name() + "("
            + function.type().formatLabeledParameters(parameterTypes, omittable)
            + ")";
    }

    public boolean isOverrideCompatible(
        final FunctionType implementation,
        final FunctionType inherited
    ) {
        final Type implementationReturn = implementation.returnType();
        final Type inheritedReturn = inherited.returnType();
        final boolean compatibleReturn =
            (implementationReturn instanceof PromiseType implementationPromise
                && inheritedReturn instanceof PromiseType inheritedPromise)
                    ? isSubtype(
                        implementationPromise.valueType(),
                        inheritedPromise.valueType()
                    )
                    : isSubtype(implementationReturn, inheritedReturn);
        return implementation.sameOverloadSignature(inherited)
            && compatibleReturn;
    }

    public void addOverrideBridge(
        final FunctionSymbol function,
        final FunctionType inherited
    ) {
        if (function.type().equals(inherited)) {
            return;
        }
        final List<FunctionType> bridges =
            overrideBridges.computeIfAbsent(function, _ -> new ArrayList<>());
        if (!bridges.contains(inherited)) {
            bridges.add(inherited);
        }
    }

    public List<FunctionType> getOverrideBridges(
        final FunctionSymbol function
    ) {
        return overrideBridges.getOrDefault(function, List.of());
    }

    public void addInterfaceBridge(
        final ClassType owner,
        final FunctionSymbol implementation,
        final FunctionSymbol contract
    ) {
        final InterfaceBridge bridge =
            new InterfaceBridge(implementation, contract);
        final List<InterfaceBridge> bridges =
            interfaceBridges.computeIfAbsent(owner, _ -> new ArrayList<>());
        if (!bridges.contains(bridge)) {
            bridges.add(bridge);
        }
    }

    public List<InterfaceBridge> getInterfaceBridges(final ClassType owner) {
        return interfaceBridges.getOrDefault(owner, List.of());
    }

    public record InterfaceBridge(
        FunctionSymbol implementation, FunctionSymbol contract
    ) {
    }

    public void setInterfaceMethodOwner(
        final FunctionSymbol method,
        final InterfaceType owner
    ) {
        interfaceMethodOwners.put(method, owner);
    }

    public @Nullable InterfaceType findInterfaceMethodOwner(
        final FunctionSymbol method
    ) {
        return interfaceMethodOwners.get(method);
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

    public void setPermittedSubclasses(
        final Type type,
        final List<String> names
    ) {
        permittedSubclasses.put(type, List.copyOf(names));
    }

    public boolean permitsSubclass(final Type type, final String name) {
        final List<String> permitted = permittedSubclasses.get(type);
        return permitted == null || permitted.contains(name);
    }

    public boolean isSealed(final Type type) {
        return permittedSubclasses.containsKey(type);
    }

    public @Nullable ClassDeclaration findClassDeclaration(
        final ClassType type
    ) {
        return classDeclarations.get(type);
    }

    public @Nullable FunctionSymbol findStaticEquality(final ClassType type) {
        for (ClassType current = type; current != null; current =
            getSuperclass(current)) {
            final ClassDeclaration declaration = findClassDeclaration(current);
            if (declaration == null) {
                continue;
            }
            for (final MemberDeclaration member : declaration.members()) {
                if (
                    member.declaration() instanceof FunctionDeclaration function
                        && getSymbol(
                            function.name()
                        ) instanceof FunctionSymbol symbol
                        && symbol.name().equals("equal")
                        && isStaticMember(symbol)
                        && symbol.type().parameterTypes().size() == 2
                        && symbol.type().parameterTypes().get(0).equals(current)
                        && symbol.type().parameterTypes().get(1).equals(current)
                        && symbol.type().returnType() == BuiltinType.BOOL
                ) {
                    return symbol;
                }
            }
        }
        return null;
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

    public void setEnumClass(
        final EnumDeclaration enumeration,
        final ClassDeclaration declaration
    ) {
        enumClasses.put(enumeration, declaration);
        enumDeclarations.put(declaration, enumeration);
    }

    public ClassDeclaration getEnumClass(final EnumDeclaration enumeration) {
        return Objects.requireNonNull(enumClasses.get(enumeration));
    }

    public @Nullable EnumDeclaration findEnumDeclaration(
        final ClassDeclaration declaration
    ) {
        return enumDeclarations.get(declaration);
    }

    public boolean isEnumClass(final ClassType type) {
        return enumClasses.values().stream().anyMatch(declaration -> {
            final Symbol symbol = declarations.get(declaration.name());
            return symbol != null && symbol.type().equals(type);
        });
    }

    public void setEnumConstantCreation(
        final NewExpression creation,
        final String name,
        final int ordinal
    ) {
        enumConstantCreations.add(creation);
        enumNames.put(creation, name);
        enumOrdinals.put(creation, ordinal);
    }

    public boolean isEnumConstantCreation(final NewExpression creation) {
        return enumConstantCreations.contains(creation);
    }

    public String getEnumConstantName(final NewExpression creation) {
        return Objects.requireNonNull(enumNames.get(creation));
    }

    public int getEnumOrdinal(final NewExpression creation) {
        return Objects.requireNonNull(enumOrdinals.get(creation));
    }

    public boolean isEnumConstant(final ClassType type, final String name) {
        return enumClasses.entrySet().stream().anyMatch(entry -> {
            final Symbol symbol = declarations.get(entry.getValue().name());
            return symbol != null && symbol.type().equals(type)
                && entry.getKey()
                    .constants()
                    .stream()
                    .anyMatch(constant -> constant.name().name().equals(name));
        });
    }

    public void setEnumIntrinsic(final FunctionDeclaration method) {
        enumIntrinsics.add(method);
    }

    public boolean isEnumIntrinsic(final FunctionDeclaration method) {
        return enumIntrinsics.contains(method);
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

    public void setMemberDeclaration(
        final AstNode declaration,
        final MemberDeclaration member
    ) {
        memberDeclarations.put(declaration, member);
    }

    public ClassType getClassMemberOwner(final Symbol symbol) {
        return Objects.requireNonNull(classMemberOwners.get(symbol));
    }

    public @Nullable ClassType findClassMemberOwner(final Symbol symbol) {
        return classMemberOwners.get(symbol);
    }

    private final Set<Symbol> staticMembers =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Symbol> uninitializedStaticFields =
        Collections.newSetFromMap(new IdentityHashMap<>());

    public void setStaticMember(final Symbol symbol) {
        staticMembers.add(symbol);
    }

    public boolean isStaticMember(final Symbol symbol) {
        return staticMembers.contains(symbol);
    }

    public void setUninitializedStaticField(final Symbol symbol) {
        uninitializedStaticFields.add(symbol);
    }

    public boolean isUninitializedStaticField(final Symbol symbol) {
        return uninitializedStaticFields.contains(symbol);
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
        if (source instanceof LiteralType literal) {
            return isSubtype(literal.valueType(), target);
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
                    if (
                        JavaTypes.isClassType(type)
                            && JavaTypes.isClassType(contract)
                    ) {
                        final Type represented =
                            contract.typeArguments().getFirst();
                        return represented == BuiltinType.ANY || isSubtype(
                            type.typeArguments().getFirst(),
                            represented
                        );
                    }
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

    public void markCapturedMutable(final VariableSymbol variable) {
        capturedMutable.add(variable);
    }

    public @Nullable Symbol findDeclaredSymbol(final AstNode node) {
        return declarations.get(node);
    }

    public void setParameterDetails(final FunctionParameter parameter) {
        parameterDetails.put(parameter.name(), parameter);
    }

    public FunctionParameter getParameterDetails(
        final BindingDeclaration name
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
        memberReferenceOwners.put(expression.member(), owner);
    }

    public Type getMemberOwner(final MemberExpression expression) {
        return Objects.requireNonNull(memberOwners.get(expression));
    }

    public void setExpressionType(
        final Expression expression,
        final Type type
    ) {
        if (typeProbeDepth > 0) {
            probedExpressionTypes.put(expression, type);
        }
        else {
            expressionTypes.put(expression, type);
        }
    }

    public Type getExpressionType(final Expression expression) {
        if (typeProbeDepth > 0) {
            final Type probed = probedExpressionTypes.get(expression);
            if (probed != null) {
                return probed;
            }
        }
        return Objects.requireNonNull(expressionTypes.get(expression));
    }

    public void setSwitchTypeMatch(final Expression expression) {
        switchTypeMatches.add(expression);
    }

    public boolean isSwitchTypeMatch(final Expression expression) {
        return switchTypeMatches.contains(expression);
    }

    public void setSwitchDualMatch(final Expression expression) {
        switchDualMatches.add(expression);
    }

    public boolean isSwitchDualMatch(final Expression expression) {
        return switchDualMatches.contains(expression);
    }

    public void setNarrowedType(
        final IdentifierExpression expression,
        final Type type
    ) {
        narrowedTypes.put(expression, type);
    }

    public @Nullable Type findNarrowedType(
        final IdentifierExpression expression
    ) {
        return narrowedTypes.get(expression);
    }

    public void clearNarrowedType(final IdentifierExpression expression) {
        narrowedTypes.remove(expression);
    }

    public void setConversionType(
        final Expression expression,
        final Type type
    ) {
        if (typeProbeDepth == 0) {
            conversionTypes.put(expression, type);
        }
    }

    public boolean probeTypeAssignment(final BooleanSupplier check) {
        final IdentityHashMap<Expression, Type> previousTypes =
            new IdentityHashMap<>(probedExpressionTypes);
        typeProbeDepth++;
        try {
            return check.getAsBoolean();
        }
        finally {
            typeProbeDepth--;
            probedExpressionTypes.clear();
            probedExpressionTypes.putAll(previousTypes);
        }
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
        if (typeProbeDepth == 0) {
            unionMemberTypes.put(expression, member);
            setConversionType(expression, union);
        }
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

    public void setSyntheticDeclaration(final AstNode declaration) {
        syntheticDeclarations.add(declaration);
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

    public void setReference(
        final IdentifierPattern pattern,
        final Symbol symbol
    ) {
        references.put(pattern, symbol);
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
        final List<BindingDeclaration> parameters
    ) {
        functionParameters.put(function, List.copyOf(parameters));
    }

    public List<BindingDeclaration> getFunctionParameters(
        final FunctionSymbol function
    ) {
        return Objects.requireNonNull(functionParameters.get(function));
    }

    public void setNamedArgument(
        final IdentifierDeclaration argument,
        final IdentifierDeclaration parameter
    ) {
        // Record lowering uses a parameter's source range for generated copy arguments.
        if (argument.range().equals(parameter.range())) {
            return;
        }
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

    public void setSuperConstructorArgumentParameters(
        final SuperConstructorCall call,
        final List<Integer> parameters
    ) {
        superConstructorArgumentParameters.put(call, List.copyOf(parameters));
    }

    public List<Integer> getSuperConstructorArgumentParameters(
        final SuperConstructorCall call
    ) {
        return Objects
            .requireNonNull(superConstructorArgumentParameters.get(call));
    }

    @Override
    public String toString() {
        final List<String> lines = new ArrayList<>();
        final IdentityHashMap<AstNode, Symbol> sourceDeclarations =
            new IdentityHashMap<>(declarations);
        syntheticDeclarations.forEach(sourceDeclarations::remove);
        appendSection(lines, "Expression types:", expressionTypes);
        appendSection(
            lines,
            "Narrowed types:",
            narrowedTypes,
            (expression, type) -> getReference(expression).type() + ARROW + type
        );
        appendSection(lines, "Resolved types:", resolvedTypes);

        appendSection(
            lines,
            "Conversions:",
            conversionTypes,
            (expression, type) -> {
                final @Nullable Type source = expressionTypes.get(expression);
                final @Nullable Type member = unionMemberTypes.get(expression);
                return member == null || Objects.equals(source, member)
                    ? source + ARROW + type
                    : source + ARROW + member + ARROW + type;
            }
        );
        appendSection(
            lines,
            "Declarations:",
            sourceDeclarations,
            (declaration, symbol) -> {
                final MemberDeclaration member =
                    memberDeclarations.get(declaration);
                return (member == null ? "" : member.getDeclarationPrefix())
                    + formatDeclaration(symbol);
            }
        );
        appendSection(
            lines,
            "References:",
            references,
            (expression, symbol) -> {
                if (symbol instanceof BuiltinFunctionSymbol) {
                    return "builtin " + symbol.name();
                }
                if (symbol instanceof JavaClassSymbol javaClass) {
                    final Class<?> type =
                        Objects.requireNonNull(javaClass.type().javaClass());
                    return formatJavaClassName(type);
                }
                if (
                    expression instanceof IdentifierExpression identifier
                        && symbol instanceof JavaMethodSymbol method
                ) {
                    final Type owner = memberReferenceOwners.get(identifier);
                    if (owner != null) {
                        final List<Type> parameters = new ArrayList<>();
                        if (method.receiverAsFirstArgument()) {
                            parameters.add(owner);
                        }
                        parameters.addAll(method.type().parameterTypes());
                        return "%s.%s(%s): %s".formatted(
                            formatJavaClassName(
                                method.method().getDeclaringClass()
                            ),
                            method.name(),
                            parameters.stream()
                                .map(Type::toString)
                                .collect(Collectors.joining(", ")),
                            method.type().returnType()
                        );
                    }
                }
                return symbol.range().toString();
            }
        );
        appendSection(
            lines,
            "Named arguments:",
            namedArguments,
            (argument, parameter) -> "parameter " + parameter.range()
        );
        return Objects.requireNonNull(String.join("\n", lines).stripTrailing());
    }

    private static String formatJavaClassName(final Class<?> type) {
        return type.getPackageName()
            .equals("com.github.andreasarvidsson.eld.runtime")
                ? type.getSimpleName()
                : type.getName();
    }

    private String formatDeclaration(final Symbol symbol) {
        if (symbol instanceof FunctionSymbol function) {
            final FunctionDeclaration declaration =
                getFunctionDeclaration(function);
            if (declaration != null) {
                final List<String> parameterTypes =
                    declaration.parameters()
                        .stream()
                        .map(this::formatParameterType)
                        .toList();
                return function.format(
                    parameterTypes,
                    declaration.parameters()
                        .stream()
                        .map(FunctionParameter::omittable)
                        .toList()
                );
            }
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

    private String formatParameterType(final FunctionParameter parameter) {
        final Type declared = declaredParameterTypes.get(parameter);
        if (declared == null) {
            return parameter.type().toString();
        }
        final String text = declared.toString();
        return parameter.omittable() && declared instanceof UnionType
            ? "(" + text + ")"
            : text;
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
            .map(
                entry -> "  " + Objects.requireNonNull(entry.getKey()).range()
                    + ARROW
                    + formatValue.apply(entry.getKey(), entry.getValue())
            )
            .distinct()
            .forEach(lines::add);
        lines.add("");
    }

}
