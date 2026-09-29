package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.runtime.RuntimeAbi;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.Declaration;
import com.github.andreasarvidsson.eld.parser.EnumDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.InterfaceDeclaration;
import com.github.andreasarvidsson.eld.parser.InterfaceMethodDeclaration;
import com.github.andreasarvidsson.eld.parser.MemberDeclaration;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.RecordDeclaration;
import com.github.andreasarvidsson.eld.parser.StaticInitializerDeclaration;
import com.github.andreasarvidsson.eld.parser.TypeNode;
import com.github.andreasarvidsson.eld.parser.UninitializedVariableDeclaration;
import com.github.andreasarvidsson.eld.parser.VariableDeclaration;
import com.github.andreasarvidsson.eld.parser.Visibility;

public final class SemanticAnalyzerDeclarations {
    private final SemanticAnalyzer analyzer;
    private final SemanticModel model;

    public SemanticAnalyzerDeclarations(
        final SemanticAnalyzer analyzer,
        final SemanticModel model
    ) {
        this.analyzer = analyzer;
        this.model = model;
    }

    public void analyzeClassDeclaration(
        final ClassDeclaration declaration,
        final SemanticContext context
    ) {
        analyzeClassDeclaration(declaration, context, null);
    }

    public void analyzeRecordDeclaration(
        final RecordDeclaration record,
        final ClassDeclaration declaration,
        final SemanticContext context
    ) {
        analyzeClassDeclaration(declaration, context, record);
    }

    private void analyzeClassDeclaration(
        final ClassDeclaration declaration,
        final SemanticContext context,
        final @Nullable RecordDeclaration record
    ) {
        final @Nullable ClassType previous = analyzer.currentAccessClass();
        analyzer
            .setCurrentAccessClass(new ClassType(declaration.name().name()));
        try {
            analyzeClassMembers(declaration, context, record);
        }
        finally {
            analyzer.setCurrentAccessClass(previous);
        }
    }

    private void analyzeClassMembers(
        final ClassDeclaration declaration,
        final SemanticContext context,
        final @Nullable RecordDeclaration record
    ) {
        final ClassType classType = new ClassType(declaration.name().name());
        final EnumDeclaration enumeration =
            model.findEnumDeclaration(declaration);
        final ClassDeclarationSymbol classSymbol =
            record != null
                ? new RecordSymbol(record, classType)
                : enumeration != null
                    ? new EnumSymbol(enumeration, classType)
                    : new ClassSymbol(
                        declaration.name(),
                        classType,
                        declaration.modifiers()
                    );
        model.setSymbol(declaration.name(), classSymbol);
        model.setClassDeclaration(classType, declaration);
        context.scope().declare(classSymbol);
        final List<InterfaceType> implemented = new ArrayList<>();
        for (final TypeNode node : declaration.implementedInterfaces()) {
            final Type type = analyzer.resolveType(node, context);
            if (!(type instanceof InterfaceType contract)) {
                throw new SemanticException(
                    node.range(),
                    "'implements' requires an interface"
                );
            }
            if (
                contract.javaClass() != null
                    && contract.javaClass() != Comparable.class
                    && contract.javaClass() != java.util.Comparator.class
            ) {
                throw new SemanticException(
                    node.range(),
                    "Only Comparable and Comparator can be implemented from the Java collection API"
                );
            }
            if (
                contract.javaClass() != null && implemented.stream()
                    .anyMatch(
                        previous -> previous.javaClass() == contract.javaClass()
                    )
            ) {
                throw new SemanticException(
                    node.range(),
                    "Duplicate implemented Java interface: %s",
                    contract.name()
                );
            }
            if (implemented.contains(contract)) {
                throw new SemanticException(
                    node.range(),
                    "Duplicate implemented interface: %s",
                    contract
                );
            }
            implemented.add(contract);
        }
        model.setImplementedInterfaces(classType, implemented);
        final IdentifierExpression superclassName = declaration.superClass();
        if (superclassName != null) {
            analyzer.analyzeIdentifierExpression(superclassName, context);
            if (
                !(model.getReference(
                    superclassName
                ) instanceof ClassDeclarationSymbol superclassSymbol)
                    || superclassSymbol instanceof RecordSymbol
            ) {
                throw new SemanticException(
                    superclassName.range(),
                    "'extends' requires a class name"
                );
            }
            final ClassType superclass = superclassSymbol.type();
            if (model.isEnumClass(superclass)) {
                throw new SemanticException(
                    superclassName.range(),
                    "Enums cannot be extended"
                );
            }
            model.setExpressionType(superclassName, superclass);
            if (
                superclass.equals(classType)
                    || model.isSubclassOf(superclass, classType)
            ) {
                throw new SemanticException(
                    superclassName.range(),
                    "Class inheritance cannot be cyclic"
                );
            }
            model.setSuperclass(classType, superclass);
            if (
                !analyzer.canAccess(
                    superclass,
                    model.getConstructorVisibility(superclass)
                )
            ) {
                throw new SemanticException(
                    superclassName.range(),
                    "Base constructor of class %s is private",
                    superclass.name()
                );
            }
        }
        final Scope members = Scope.classMembers();
        analyzer.classScopes().put(classType, members);
        analyzer.classMethods().put(classType, new LinkedHashMap<>());
        ConstructorDeclaration constructor = null;
        model.setConstructorVisibility(classType, Visibility.PUBLIC);
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            final Declaration member = memberDeclaration.declaration();
            if (member instanceof ConstructorDeclaration candidate) {
                model.setMemberDeclaration(candidate, memberDeclaration);
                if (constructor != null) {
                    throw new SemanticException(
                        candidate.range(),
                        "A class may only declare one constructor"
                    );
                }
                constructor = candidate;
                model.setConstructorVisibility(
                    classType,
                    memberDeclaration.visibility()
                );
            }
        }
        final boolean explicitSuper =
            constructor != null && constructor.hasExplicitSuperCall();
        final ClassType superclass = model.getSuperclass(classType);
        if (explicitSuper && superclass == null) {
            throw new SemanticException(
                Objects.requireNonNull(constructor).range(),
                "'super(...)' requires a superclass"
            );
        }
        if (superclass != null && !explicitSuper) {
            final List<FunctionParameter> inheritedParameters =
                model.getConstructorParameters(superclass);
            for (int i = 0; i < inheritedParameters.size(); i++) {
                final FunctionParameter parameter = inheritedParameters.get(i);
                if (!parameter.omittable()) {
                    throw new SemanticException(
                        Objects.requireNonNull(superclassName).range(),
                        "Base constructor requires argument: %s",
                        parameter.displayName(i)
                    );
                }
            }
        }
        final List<Type> parameterTypes = new ArrayList<>();
        if (constructor != null) {
            for (final FunctionParameter parameter : constructor.parameters()) {
                final Type type =
                    analyzer.resolveParameterType(parameter, context);
                parameterTypes.add(type);
                if (!parameter.discarded()) {
                    model.setSymbol(
                        parameter.identifier(),
                        new VariableSymbol(
                            parameter.identifier(),
                            type,
                            Mutability.CONST
                        )
                    );
                }
            }
        }
        final FunctionType constructorType =
            new FunctionType(parameterTypes, BuiltinType.VOID);
        if (classSymbol instanceof RecordSymbol recordSymbol) {
            recordSymbol.setComponentTypes(parameterTypes);
        }
        model.setConstructor(classType, constructorType);
        model.setConstructorParameters(
            classType,
            constructor != null ? constructor.parameters() : List.of()
        );
        if (constructor != null) {
            model.setConstructorSymbol(
                constructor,
                new ConstructorSymbol(
                    constructor,
                    constructorType,
                    constructor.parameters()
                        .stream()
                        .map(model::getDeclaredParameterType)
                        .toList()
                )
            );
        }
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            if (
                memberDeclaration
                    .declaration() instanceof FunctionDeclaration method
            ) {
                if (method.abstractMethod()) {
                    if (!declaration.abstractClass()) {
                        throw new SemanticException(
                            method.range(),
                            "Abstract methods require an abstract class"
                        );
                    }
                    if (
                        memberDeclaration.staticMember()
                            || memberDeclaration
                                .visibility() == Visibility.PRIVATE
                            || method.finalMethod()
                            || method.async()
                    ) {
                        throw new SemanticException(
                            method.range(),
                            "Abstract methods cannot be private, static, final, or async"
                        );
                    }
                    if (
                        method.parameters()
                            .stream()
                            .anyMatch(FunctionParameter::omittable)
                    ) {
                        throw new SemanticException(
                            method.range(),
                            "Abstract methods cannot have optional parameters or parameter defaults"
                        );
                    }
                }
                analyzer.registerFunction(method, context, members);
                final FunctionSymbol function =
                    (FunctionSymbol) model.getSymbol(method.name());
                setClassMemberMetadata(
                    memberDeclaration,
                    method.name(),
                    classType
                );
                Objects.requireNonNull(analyzer.classMethods().get(classType))
                    .putIfAbsent(function.name(), function);
            }
        }
        final Scope enumFieldScope = new Scope(context.scope());
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            final Declaration member = memberDeclaration.declaration();
            if (member instanceof VariableDeclaration field) {
                final SemanticContext fieldContext =
                    model.isEnumClass(classType)
                        && memberDeclaration.staticMember()
                            ? new SemanticContext(enumFieldScope, null, 0)
                            : context;
                analyzer
                    .analyzeVariableDeclaration(field, fieldContext, members);
                setClassMemberMetadata(
                    memberDeclaration,
                    (IdentifierDeclaration) field.name(),
                    classType
                );
                if (
                    model.isEnumClass(classType)
                        && memberDeclaration.staticMember()
                ) {
                    enumFieldScope.declare(
                        model.getSymbol((IdentifierDeclaration) field.name())
                    );
                }
            }
            else if (member instanceof UninitializedVariableDeclaration field) {
                final Type type = analyzer.resolveType(field.type(), context);
                final VariableSymbol symbol =
                    new VariableSymbol(field.name(), type, field.mutability());
                members.declare(symbol);
                model.setSymbol(field.name(), symbol);
                setClassMemberMetadata(
                    memberDeclaration,
                    field.name(),
                    classType
                );
                if (
                    memberDeclaration.staticMember()
                        && field.mutability() == Mutability.CONST
                ) {
                    model.setUninitializedStaticField(symbol);
                }
            }
        }
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            final Declaration member = memberDeclaration.declaration();
            final IdentifierDeclaration name = switch (member) {
                case VariableDeclaration field ->
                    (IdentifierDeclaration) field.name();
                case UninitializedVariableDeclaration field -> field.name();
                case FunctionDeclaration method -> method.name();
                default -> null;
            };
            if (name != null) {
                analyzer
                    .validateInheritedMember(classType, model.getSymbol(name));
            }
        }
        validateAbstractMethods(classType, declaration);
        validateInterfaces(classType, declaration);
        final Scope enumScope = new Scope(context.scope());
        if (model.isEnumClass(classType)) {
            for (final Symbol symbol : members.symbols()) {
                if (model.isStaticMember(symbol)) {
                    enumScope.declare(symbol);
                }
            }
        }
        final SemanticContext bodyContext =
            model.isEnumClass(classType)
                ? new SemanticContext(
                    enumScope,
                    context.function(),
                    context.loopDepth()
                )
                : context;
        final @Nullable ClassType previousInstance = analyzer.currentInstance();
        final @Nullable ConstructorDeclaration previousConstructor =
            analyzer.currentConstructor();
        analyzer.setCurrentInstance(classType);
        try {
            for (final MemberDeclaration memberDeclaration : declaration
                .members()) {
                final Declaration member = memberDeclaration.declaration();
                if (member instanceof FunctionDeclaration method) {
                    final @Nullable ClassType methodInstance =
                        memberDeclaration.staticMember() ? null : classType;
                    analyzer.setCurrentInstance(methodInstance);
                    if (
                        !model.isEnumIntrinsic(method)
                            && !method.abstractMethod()
                    ) {
                        analyzer.analyzeFunctionBody(method, bodyContext);
                    }
                }
                else if (
                    member instanceof StaticInitializerDeclaration initializer
                ) {
                    analyzer.setCurrentInstance(null);
                    analyzer.setAnalyzingStaticInitializer(true);
                    try {
                        analyzer.analyzeBlockStatement(
                            initializer.body(),
                            new SemanticContext(
                                new Scope(bodyContext.scope()),
                                null,
                                0
                            )
                        );
                    }
                    finally {
                        analyzer.setAnalyzingStaticInitializer(false);
                    }
                }
            }
            analyzer.setCurrentInstance(classType);
            if (constructor != null) {
                analyzer.setCurrentConstructor(constructor);
                final Scope scope = new Scope(context.scope());
                for (final FunctionParameter parameter : constructor
                    .parameters()) {
                    analyzer.analyzeParameterDefault(
                        parameter,
                        new SemanticContext(scope, null, 0)
                    );
                    if (!parameter.discarded()) {
                        scope.declare(model.getSymbol(parameter.identifier()));
                    }
                }
                analyzer.analyzeBlockStatement(
                    constructor.body(),
                    new SemanticContext(scope, null, 0)
                );
            }
            new FieldInitializationAnalyzer(model, declaration)
                .analyze(constructor);
            new FieldInitializationAnalyzer(model, declaration, true)
                .analyzeStaticInitializers();
        }
        finally {
            analyzer.setCurrentInstance(previousInstance);
            analyzer.setCurrentConstructor(previousConstructor);
        }
    }

    private void validateAbstractMethods(
        final ClassType classType,
        final ClassDeclaration declaration
    ) {
        if (declaration.abstractClass()) {
            return;
        }
        for (ClassType current = classType; current != null; current =
            model.getSuperclass(current)) {
            final ClassDeclaration inheritedClass =
                model.findClassDeclaration(current);
            if (inheritedClass == null) {
                continue;
            }
            for (final MemberDeclaration member : inheritedClass.members()) {
                if (
                    !(member
                        .declaration() instanceof FunctionDeclaration source)
                        || !source.abstractMethod()
                ) {
                    continue;
                }
                final FunctionSymbol method =
                    (FunctionSymbol) model.getSymbol(source.name());
                boolean implemented = false;
                for (ClassType owner = classType; owner != null; owner =
                    model.getSuperclass(owner)) {
                    final Scope candidateScope = analyzer.classScope(owner);
                    if (candidateScope != null) {
                        for (final FunctionSymbol candidate : candidateScope
                            .functionsLocal(method.name())) {
                            if (
                                candidate.type()
                                    .sameOverloadSignature(method.type())
                            ) {
                                final FunctionDeclaration implementation =
                                    model.getFunctionDeclaration(candidate);
                                implemented =
                                    implementation != null
                                        && !implementation.abstractMethod();
                                break;
                            }
                        }
                    }
                    if (implemented || owner.equals(current)) {
                        break;
                    }
                }
                if (!implemented) {
                    throw new SemanticException(
                        declaration.range(),
                        "Class %s must implement abstract method '%s'",
                        classType.name(),
                        method.name()
                    );
                }
            }
        }
    }

    private void setClassMemberMetadata(
        final MemberDeclaration memberDeclaration,
        final IdentifierDeclaration name,
        final ClassType classType
    ) {
        final Symbol symbol = model.getSymbol(name);
        model.setMemberDeclaration(name, memberDeclaration);
        model.setMemberVisibility(symbol, memberDeclaration.visibility());
        model.setClassMemberOwner(symbol, classType);
        if (memberDeclaration.staticMember()) {
            model.setStaticMember(symbol);
        }
    }

    public void analyzeInterfaceDeclaration(
        final InterfaceDeclaration declaration,
        final SemanticContext context
    ) {
        final InterfaceType type = new InterfaceType(declaration.name().name());
        final InterfaceSymbol symbol =
            new InterfaceSymbol(declaration.name(), type);
        context.scope().declare(symbol);
        model.setSymbol(declaration.name(), symbol);
        final List<InterfaceType> parents = new ArrayList<>();
        final Map<String, VariableSymbol> fields = new LinkedHashMap<>();
        final Map<String, FunctionSymbol> methods = new LinkedHashMap<>();
        final Map<String, List<FunctionSymbol>> overloads =
            new LinkedHashMap<>();
        final Map<String, List<FunctionSymbol>> inheritedMethods =
            new LinkedHashMap<>();
        for (final TypeNode node : declaration.superInterfaces()) {
            final Type parent = analyzer.resolveType(node, context);
            if (!(parent instanceof InterfaceType contract)) {
                throw new SemanticException(
                    node.range(),
                    "Interfaces may only extend interfaces"
                );
            }
            if (contract.equals(type)) {
                throw new SemanticException(
                    node.range(),
                    "Interface inheritance cannot be cyclic"
                );
            }
            if (parents.contains(contract)) {
                throw new SemanticException(
                    node.range(),
                    "Duplicate superinterface: %s",
                    contract
                );
            }
            if (
                contract.javaClass() != null
                    && contract.javaClass() != Comparable.class
                    && contract.javaClass() != Comparator.class
            ) {
                throw new SemanticException(
                    node.range(),
                    "Only Comparable and Comparator can be extended from the Java collection API"
                );
            }
            parents.add(contract);
            mergeContract(fields, model.getInterface(contract).fields(), node);
            model.getInterface(contract)
                .overloads()
                .forEach(
                    (name, inherited) -> inheritedMethods
                        .computeIfAbsent(name, _ -> new ArrayList<>())
                        .addAll(inherited)
                );
        }
        final Set<String> ownFields = new java.util.HashSet<>();
        final Set<FunctionSymbol> ownMethods = new HashSet<>();
        for (final var member : declaration.members()) {
            if (member instanceof UninitializedVariableDeclaration field) {
                if (!ownFields.add(field.name().name())) {
                    throw new SemanticException(
                        field.range(),
                        "Duplicate interface field: %s",
                        field.name().name()
                    );
                }
                final VariableSymbol value =
                    new VariableSymbol(
                        field.name(),
                        analyzer.resolveType(field.type(), context),
                        field.mutability()
                    );
                mergeContract(fields, Map.of(value.name(), value), field);
                fields.put(value.name(), value);
                model.setSymbol(field.name(), value);
                model.setMemberVisibility(value, Visibility.PUBLIC);
            }
            else if (member instanceof InterfaceMethodDeclaration method) {
                if (method.name().name().equals("equals")) {
                    throw new SemanticException(
                        method.name().range(),
                        "Interface cannot declare ubiquitous method 'equals'"
                    );
                }
                final List<Type> parameters = new ArrayList<>();
                final Scope scope = new Scope(context.scope());
                for (final FunctionParameter parameter : method.parameters()) {
                    final Type parameterType =
                        analyzer.resolveParameterType(parameter, context);
                    parameters.add(parameterType);
                    analyzer.analyzeParameterDefault(
                        parameter,
                        new SemanticContext(scope, null, 0)
                    );
                    if (!parameter.discarded()) {
                        final VariableSymbol value =
                            new VariableSymbol(
                                parameter.identifier(),
                                parameterType,
                                Mutability.CONST
                            );
                        model.setSymbol(parameter.identifier(), value);
                        scope.declare(value);
                    }
                }
                final FunctionSymbol value =
                    new FunctionSymbol(
                        method.name(),
                        FunctionType.declared(
                            parameters,
                            method.returnType() == null
                                ? BuiltinType.VOID
                                : analyzer.resolveReturnType(
                                    method.returnType(),
                                    context
                                ),
                            method.parameters()
                        )
                    );
                model.setSymbol(method.name(), value);
                model.setInterfaceMethodOwner(value, type);
                model.setMemberVisibility(value, Visibility.PUBLIC);
                model.setFunctionParameters(
                    value,
                    method.parameters()
                        .stream()
                        .map(FunctionParameter::name)
                        .toList()
                );
                final List<FunctionSymbol> declared =
                    overloads
                        .computeIfAbsent(value.name(), _ -> new ArrayList<>());
                for (final FunctionSymbol existing : declared) {
                    final List<FunctionParameter> existingParameters =
                        model.getFunctionParameters(existing)
                            .stream()
                            .map(model::getParameterDetails)
                            .toList();
                    if (existing.type().equals(value.type())) {
                        throw new SemanticException(
                            method.range(),
                            "Duplicate interface method: %s",
                            value.name()
                        );
                    }
                    if (
                        FunctionSignatures
                            .overlap(
                                existing.type(),
                                existingParameters,
                                value.type(),
                                method.parameters(),
                                parameterType -> parameterType
                            )
                            || FunctionSignatures.ambiguous(
                                existing.type(),
                                existingParameters,
                                value.type(),
                                method.parameters(),
                                (source, target) -> FunctionSignatures
                                    .isSubtype(source, target, model)
                            )
                    ) {
                        throw new SemanticException(
                            method.range(),
                            "Ambiguous overload of '%s': overlapping callable signatures",
                            value.name()
                        );
                    }
                }
                declared.add(value);
                ownMethods.add(value);
            }
        }
        for (final var entry : inheritedMethods.entrySet()) {
            final List<FunctionSymbol> declared =
                overloads
                    .computeIfAbsent(entry.getKey(), _ -> new ArrayList<>());
            for (final FunctionSymbol inherited : entry.getValue()) {
                int override = -1;
                for (int i = 0; i < declared.size(); i++) {
                    if (
                        declared.get(i)
                            .type()
                            .sameOverloadSignature(inherited.type())
                    ) {
                        override = i;
                        break;
                    }
                }
                if (override >= 0) {
                    final FunctionSymbol existing = declared.get(override);
                    if (
                        model.isOverrideCompatible(
                            existing.type(),
                            inherited.type()
                        )
                    ) {
                        continue;
                    }
                    if (
                        model.isOverrideCompatible(
                            inherited.type(),
                            existing.type()
                        ) && !ownMethods.contains(existing)
                    ) {
                        declared.set(override, inherited);
                    }
                    else {
                        throw new SemanticException(
                            declaration.range(),
                            "Conflicting interface member '%s'",
                            entry.getKey()
                        );
                    }
                }
                else {
                    declared.add(inherited);
                }
            }
        }
        for (final var entry : overloads.entrySet()) {
            final List<FunctionSymbol> candidates = entry.getValue();
            for (int i = 0; i < candidates.size(); i++) {
                for (int j = i + 1; j < candidates.size(); j++) {
                    validateInterfaceOverload(
                        candidates.get(i),
                        candidates.get(j),
                        declaration,
                        type
                    );
                }
            }
            methods.put(entry.getKey(), candidates.getFirst());
        }
        model.setInterface(
            type,
            new InterfaceContract(
                List.copyOf(parents),
                fields,
                methods,
                overloads
            )
        );
    }

    private void validateInterfaceOverload(
        final FunctionSymbol left,
        final FunctionSymbol right,
        final InterfaceDeclaration declaration,
        final InterfaceType owner
    ) {
        final List<FunctionParameter> leftParameters =
            model.getFunctionParameters(left)
                .stream()
                .map(model::getParameterDetails)
                .toList();
        final List<FunctionParameter> rightParameters =
            model.getFunctionParameters(right)
                .stream()
                .map(model::getParameterDetails)
                .toList();
        if (
            FunctionSignatures
                .overlap(
                    left.type(),
                    leftParameters,
                    right.type(),
                    rightParameters,
                    type -> type
                )
                || FunctionSignatures.ambiguous(
                    left.type(),
                    leftParameters,
                    right.type(),
                    rightParameters,
                    (source, target) -> FunctionSignatures
                        .isSubtype(source, target, model)
                )
        ) {
            throw new SemanticException(
                owner.equals(model.findInterfaceMethodOwner(right))
                    ? right.range()
                    : owner.equals(model.findInterfaceMethodOwner(left))
                        ? left.range()
                        : declaration.range(),
                "Ambiguous overload of '%s': overlapping callable signatures",
                left.name()
            );
        }
    }

    private <S extends Symbol> void mergeContract(
        final Map<String, S> target,
        final Map<String, S> source,
        final AstNode node
    ) {
        for (final var entry : source.entrySet()) {
            final S previous =
                target.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous == null) {
                continue;
            }
            final S current = entry.getValue();
            if (
                !previous.type().equals(current.type())
                    || (previous instanceof VariableSymbol oldField
                        && current instanceof VariableSymbol newField
                        && oldField.mutability() != newField.mutability())
            ) {
                throw new SemanticException(
                    node.range(),
                    "Conflicting interface member '%s'",
                    entry.getKey()
                );
            }
        }
    }

    private void validateInterfaces(
        final ClassType type,
        final ClassDeclaration declaration
    ) {
        final Map<String, VariableSymbol> fields = new LinkedHashMap<>();
        final List<FunctionSymbol> methods = new ArrayList<>();
        final List<InterfaceMethodRequirement> requirements = new ArrayList<>();
        for (ClassType current = type; current != null; current =
            model.getSuperclass(current)) {
            for (final InterfaceType contract : model
                .getImplementedInterfaces(current)) {
                mergeContract(
                    fields,
                    model.getInterface(contract).fields(),
                    declaration
                );
                for (final FunctionSymbol method : model.getInterface(contract)
                    .allMethods()) {
                    methods.add(method);
                    requirements
                        .add(new InterfaceMethodRequirement(contract, method));
                }
            }
        }
        for (int i = 0; i < requirements.size(); i++) {
            final InterfaceMethodRequirement left = requirements.get(i);
            for (int j = i + 1; j < requirements.size(); j++) {
                final InterfaceMethodRequirement right = requirements.get(j);
                if (
                    left.source().equals(right.source())
                        || model.isSubtype(left.source(), right.source())
                        || model.isSubtype(right.source(), left.source())
                        || !left.method().name().equals(right.method().name())
                        || left.method()
                            .type()
                            .sameOverloadSignature(right.method().type())
                ) {
                    continue;
                }
                if (
                    left.source().javaClass() != null
                        || right.source().javaClass() != null
                ) {
                    if (
                        javaInterfaceBridgeCollision(left, right)
                            || javaInterfaceBridgeCollision(right, left)
                    ) {
                        throw new SemanticException(
                            declaration.range(),
                            "Class %s cannot implement '%s' and '%s': Java bridge collision",
                            type,
                            model.formatFunctionSignature(left.method()),
                            model.formatFunctionSignature(right.method())
                        );
                    }
                    continue;
                }
                final List<FunctionParameter> leftParameters =
                    model.getFunctionParameters(left.method())
                        .stream()
                        .map(model::getParameterDetails)
                        .toList();
                final List<FunctionParameter> rightParameters =
                    model.getFunctionParameters(right.method())
                        .stream()
                        .map(model::getParameterDetails)
                        .toList();
                if (
                    FunctionSignatures.overlap(
                        left.method().type(),
                        leftParameters,
                        right.method().type(),
                        rightParameters,
                        this::erasedOverloadType
                    ) && !interfaceOverloadsBoth(left, right)
                        && !interfaceOverloadsBoth(right, left)
                ) {
                    throw new SemanticException(
                        declaration.range(),
                        "Class %s cannot implement erased overloads '%s' and '%s' from unrelated interfaces",
                        type,
                        model.formatFunctionSignature(left.method()),
                        model.formatFunctionSignature(right.method())
                    );
                }
            }
        }
        final Map<String, VariableSymbol> implementations =
            new LinkedHashMap<>();
        final List<Symbol> required = new ArrayList<>(fields.values());
        required.addAll(methods);
        for (final Symbol contract : required) {
            final ClassType owner =
                contract instanceof VariableSymbol
                    ? analyzer.classFieldOwner(type, contract.name())
                    : analyzer.memberOwner(type, contract.name());
            final FunctionSymbol matched =
                contract instanceof FunctionSymbol requiredMethod
                    ? interfaceImplementation(type, requiredMethod)
                    : null;
            if (
                declaration.abstractClass()
                    && contract instanceof FunctionSymbol
                    && matched == null
            ) {
                continue;
            }
            if (
                contract instanceof FunctionSymbol requiredMethod
                    && matched == null
                    && methods.stream()
                        .anyMatch(
                            other -> other.name().equals(requiredMethod.name())
                                && !other.type()
                                    .parameterTypes()
                                    .equals(
                                        requiredMethod.type().parameterTypes()
                                    )
                        )
            ) {
                throw new SemanticException(
                    declaration.range(),
                    "Class %s does not implement interface method %s",
                    type,
                    model.formatFunctionSignature(requiredMethod)
                );
            }
            final Symbol implementation =
                contract instanceof FunctionSymbol
                    ? matched != null
                        ? matched
                        : analyzer.classMethod(type, contract.name())
                    : owner == null
                        ? null
                        : Objects
                            .requireNonNull(analyzer.classScopes().get(owner))
                            .resolveLocal(contract.name());
            if (implementation == null) {
                throw new SemanticException(
                    declaration.range(),
                    "Class %s does not implement interface member '%s'",
                    type,
                    contract.name()
                );
            }
            if (model.isStaticMember(implementation)) {
                throw new SemanticException(
                    implementation.range(),
                    "Interface member '%s' must be an instance member",
                    contract.name()
                );
            }
            if (
                model.getMemberVisibility(implementation) != Visibility.PUBLIC
            ) {
                throw new SemanticException(
                    implementation.range(),
                    "Interface member '%s' must be public",
                    contract.name()
                );
            }
            final boolean compatible =
                (contract instanceof FunctionSymbol contractFunction
                    && implementation instanceof FunctionSymbol implementationFunction)
                        ? model.isOverrideCompatible(
                            implementationFunction.type(),
                            contractFunction.type()
                        )
                        : contract.type().equals(implementation.type())
                            && (contract instanceof FunctionSymbol) == (implementation instanceof FunctionSymbol);
            if (!compatible) {
                throw new SemanticException(
                    implementation.range(),
                    "Interface member '%s' expects %s, found %s",
                    contract.name(),
                    contract.type(),
                    implementation.type()
                );
            }
            if (implementation instanceof VariableSymbol field) {
                if (
                    contract instanceof VariableSymbol requiredField
                        && field.mutability() != requiredField.mutability()
                ) {
                    throw new SemanticException(
                        field.range(),
                        "Interface field '%s' must be %s",
                        field.name(),
                        requiredField.mutability() == Mutability.VAR
                            ? "mutable"
                            : "constant"
                    );
                }
                implementations.put(field.name(), field);
            }
        }
        addInterfaceBridges(type);
        model.setInterfaceFields(type, implementations);
    }

    private boolean interfaceOverloadsBoth(
        final InterfaceMethodRequirement overload,
        final InterfaceMethodRequirement other
    ) {
        return model.getInterface(overload.source())
            .methodOverloads(overload.method().name())
            .stream()
            .anyMatch(
                method -> method.type()
                    .sameOverloadSignature(other.method().type())
            );
    }

    private boolean javaInterfaceBridgeCollision(
        final InterfaceMethodRequirement java,
        final InterfaceMethodRequirement other
    ) {
        if (
            java.source().javaClass() != Comparable.class
                && java.source().javaClass() != Comparator.class
        ) {
            return false;
        }
        final FunctionType required = java.method().type();
        final FunctionType competing = other.method().type();
        if (
            required.returnType() != BuiltinType.I32
                || competing.returnType() != BuiltinType.I32
                || required.parameterTypes()
                    .size() != competing.parameterTypes().size()
        ) {
            return false;
        }
        boolean sameErasure = true;
        for (int i = 0; i < required.parameterTypes().size(); i++) {
            if (
                !Objects.equals(
                    erasedOverloadType(required.parameterTypes().get(i)),
                    erasedOverloadType(competing.parameterTypes().get(i))
                )
            ) {
                sameErasure = false;
                break;
            }
        }
        return sameErasure || (required.parameterTypes()
            .stream()
            .anyMatch(
                parameter -> !erasedOverloadType(parameter).equals(Object.class)
            )
            && competing.parameterTypes()
                .stream()
                .allMatch(
                    parameter -> erasedOverloadType(parameter)
                        .equals(Object.class)
                ));
    }

    private void addInterfaceBridges(final ClassType type) {
        final Set<InterfaceType> visited = new HashSet<>();
        final List<InterfaceType> pending = new ArrayList<>();
        for (ClassType current = type; current != null; current =
            model.getSuperclass(current)) {
            pending.addAll(model.getImplementedInterfaces(current));
        }
        while (!pending.isEmpty()) {
            final InterfaceType interfaceType = pending.removeLast();
            if (!visited.add(interfaceType)) {
                continue;
            }
            final InterfaceContract contract =
                model.getInterface(interfaceType);
            pending.addAll(contract.superInterfaces());
            for (final FunctionSymbol method : contract.allMethods()) {
                final FunctionSymbol implementation =
                    interfaceImplementation(type, method);
                if (
                    implementation != null && model.isOverrideCompatible(
                        implementation.type(),
                        method.type()
                    )
                ) {
                    model.addInterfaceBridge(type, implementation, method);
                }
            }
        }
    }

    private @Nullable FunctionSymbol interfaceImplementation(
        final ClassType type,
        final FunctionSymbol contract
    ) {
        for (ClassType current = type; current != null; current =
            model.getSuperclass(current)) {
            final Scope scope = analyzer.classScope(current);
            if (scope == null) {
                continue;
            }
            for (final FunctionSymbol method : scope
                .functionsLocal(contract.name())) {
                if (method.type().sameOverloadSignature(contract.type())) {
                    return method;
                }
            }
        }
        return null;
    }

    private Object erasedOverloadType(final Type type) {
        final Type unqualified = LiteralType.unwrap(ConstType.unwrap(type));
        return switch (unqualified) {
            case BuiltinType.STRING -> String.class;
            case BuiltinType.ANY, BuiltinType.NULL -> Object.class;
            case InterfaceType contract when contract.javaClass() != null ->
                contract.javaClass();
            case ArrayType array ->
                RuntimeAbi.array(array.elementType()).descriptor;
            case TupleType _ -> TupleType.class;
            case FunctionType _,BuiltinFunctionType _ -> FunctionType.class;
            case PromiseType _ -> PromiseType.class;
            case PromiseSourceType _ -> PromiseSourceType.class;
            case UnionType union -> {
                final List<Type> uniform =
                    union.memberTypes()
                        .stream()
                        .map(LiteralType::unwrap)
                        .map(ConstType::unwrap)
                        .distinct()
                        .toList();
                if (
                    uniform.size() == 1
                        && uniform.getFirst() instanceof BuiltinType builtin
                        && (builtin.isInteger() || builtin.isFloating()
                            || builtin == BuiltinType.BOOL
                            || builtin == BuiltinType.CHAR
                            || builtin == BuiltinType.STRING)
                ) {
                    yield erasedOverloadType(builtin);
                }
                final List<Type> members =
                    union.memberTypes()
                        .stream()
                        .filter(member -> member != BuiltinType.NULL)
                        .distinct()
                        .toList();
                if (members.size() != 1) {
                    yield Object.class;
                }
                final Type member = members.getFirst();
                yield member instanceof BuiltinType builtin
                    ? boxedOverloadType(builtin)
                    : erasedOverloadType(member);
            }
            default -> unqualified;
        };
    }

    private Object boxedOverloadType(final BuiltinType type) {
        return switch (type) {
            case I8 -> Byte.class;
            case I16 -> Short.class;
            case I32 -> Integer.class;
            case I64 -> Long.class;
            case F32 -> Float.class;
            case F64 -> Double.class;
            case BOOL -> Boolean.class;
            case CHAR -> Character.class;
            case STRING -> String.class;
            case NULL, ANY, VOID -> Object.class;
        };
    }

    private record InterfaceMethodRequirement(
        InterfaceType source, FunctionSymbol method
    ) {
    }

}
