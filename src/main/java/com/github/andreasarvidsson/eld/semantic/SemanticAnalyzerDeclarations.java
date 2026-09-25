package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.AstNode;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.ConstructorDeclaration;
import com.github.andreasarvidsson.eld.parser.Declaration;
import com.github.andreasarvidsson.eld.parser.FunctionDeclaration;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.InterfaceDeclaration;
import com.github.andreasarvidsson.eld.parser.InterfaceMethodDeclaration;
import com.github.andreasarvidsson.eld.parser.MemberDeclaration;
import com.github.andreasarvidsson.eld.parser.Mutability;
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
        final @Nullable ClassType previous = analyzer.currentAccessClass();
        analyzer
            .setCurrentAccessClass(new ClassType(declaration.name().name()));
        try {
            analyzeClassMembers(declaration, context);
        }
        finally {
            analyzer.setCurrentAccessClass(previous);
        }
    }

    private void analyzeClassMembers(
        final ClassDeclaration declaration,
        final SemanticContext context
    ) {
        final ClassType classType = new ClassType(declaration.name().name());
        final ClassSymbol classSymbol =
            new ClassSymbol(declaration.name(), classType);
        model.setSymbol(declaration.name(), classSymbol);
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
            final Type base =
                analyzer.analyzeIdentifierExpression(superclassName, context);
            if (
                !(model.getReference(superclassName) instanceof ClassSymbol)
                    || !(base instanceof ClassType superclass)
            ) {
                throw new SemanticException(
                    superclassName.range(),
                    "'extends' requires a class name"
                );
            }
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
        final Scope members = new Scope(null);
        analyzer.classScopes().put(classType, members);
        analyzer.classMethods().put(classType, new LinkedHashMap<>());
        ConstructorDeclaration constructor = null;
        model.setConstructorVisibility(classType, Visibility.PUBLIC);
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            final Declaration member = memberDeclaration.declaration();
            if (member instanceof ConstructorDeclaration candidate) {
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
            for (final FunctionParameter parameter : model
                .getConstructorParameters(superclass)) {
                if (!parameter.omittable()) {
                    throw new SemanticException(
                        Objects.requireNonNull(superclassName).range(),
                        "Base constructor requires argument: %s",
                        parameter.name().name()
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
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            final Declaration member = memberDeclaration.declaration();
            if (member instanceof VariableDeclaration field) {
                analyzer.analyzeVariableDeclaration(field, context, members);
            }
            else if (member instanceof UninitializedVariableDeclaration field) {
                final Type type = analyzer.resolveType(field.type(), context);
                final VariableSymbol symbol =
                    new VariableSymbol(field.name(), type, field.mutability());
                members.declare(symbol);
                model.setSymbol(field.name(), symbol);
            }
        }
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            if (
                memberDeclaration
                    .declaration() instanceof FunctionDeclaration method
            ) {
                analyzer.registerFunction(
                    method,
                    context,
                    members.resolveLocal(
                        method.name().name()
                    ) instanceof VariableSymbol ? new Scope(null) : members
                );
                final FunctionSymbol function =
                    (FunctionSymbol) model.getSymbol(method.name());
                if (
                    Objects
                        .requireNonNull(analyzer.classMethods().get(classType))
                        .putIfAbsent(function.name(), function) != null
                ) {
                    throw new SemanticException(
                        method.range(),
                        "Duplicate method: %s",
                        function.name()
                    );
                }
            }
        }
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            final Declaration member = memberDeclaration.declaration();
            final IdentifierDeclaration name = switch (member) {
                case VariableDeclaration field -> field.name();
                case UninitializedVariableDeclaration field -> field.name();
                case FunctionDeclaration method -> method.name();
                default -> null;
            };
            if (name != null) {
                model.setMemberVisibility(
                    model.getSymbol(name),
                    memberDeclaration.visibility()
                );
                model.setClassMemberOwner(model.getSymbol(name), classType);
                analyzer
                    .validateInheritedMember(classType, model.getSymbol(name));
            }
        }
        validateInterfaces(classType, declaration);
        final @Nullable ClassType previousInstance = analyzer.currentInstance();
        final @Nullable ConstructorDeclaration previousConstructor =
            analyzer.currentConstructor();
        analyzer.setCurrentInstance(classType);
        try {
            for (final MemberDeclaration memberDeclaration : declaration
                .members()) {
                final Declaration member = memberDeclaration.declaration();
                if (member instanceof FunctionDeclaration method) {
                    analyzer.analyzeFunctionBody(method, context);
                }
            }
            if (constructor != null) {
                analyzer.setCurrentConstructor(constructor);
                final Scope scope = new Scope(context.scope());
                for (final FunctionParameter parameter : constructor
                    .parameters()) {
                    analyzer.analyzeParameterDefault(
                        parameter,
                        new SemanticContext(scope, null, 0)
                    );
                    scope.declare(model.getSymbol(parameter.name()));
                }
                analyzer.analyzeBlockStatement(
                    constructor.body(),
                    new SemanticContext(scope, null, 0)
                );
            }
            new FieldInitializationAnalyzer(model, declaration)
                .analyze(constructor);
        }
        finally {
            analyzer.setCurrentInstance(previousInstance);
            analyzer.setCurrentConstructor(previousConstructor);
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
            mergeContract(
                methods,
                model.getInterface(contract).methods(),
                node
            );
        }
        final Set<String> ownFields = new java.util.HashSet<>();
        final Set<String> ownMethods = new java.util.HashSet<>();
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
                if (!ownMethods.add(method.name().name())) {
                    throw new SemanticException(
                        method.range(),
                        "Duplicate interface method: %s",
                        method.name().name()
                    );
                }
                final List<Type> parameters = new ArrayList<>();
                final Scope scope = new Scope(context.scope());
                for (final FunctionParameter parameter : method.parameters()) {
                    final Type parameterType =
                        analyzer.resolveParameterType(parameter, context);
                    parameters.add(parameterType);
                    final VariableSymbol value =
                        new VariableSymbol(
                            parameter.name(),
                            parameterType,
                            Mutability.CONST
                        );
                    model.setSymbol(parameter.name(), value);
                    analyzer.analyzeParameterDefault(
                        parameter,
                        new SemanticContext(scope, null, 0)
                    );
                    scope.declare(value);
                }
                final FunctionSymbol value =
                    new FunctionSymbol(
                        method.name(),
                        new FunctionType(
                            parameters,
                            method.returnType() == null
                                ? BuiltinType.VOID
                                : analyzer
                                    .resolveType(method.returnType(), context)
                        )
                    );
                mergeContract(methods, Map.of(value.name(), value), method);
                methods.put(value.name(), value);
                model.setSymbol(method.name(), value);
                model.setMemberVisibility(value, Visibility.PUBLIC);
                model.setFunctionParameters(
                    value,
                    method.parameters()
                        .stream()
                        .map(FunctionParameter::name)
                        .toList()
                );
            }
        }
        model.setInterface(
            type,
            new InterfaceContract(List.copyOf(parents), fields, methods)
        );
    }

    private <S extends Symbol> void mergeContract(
        final Map<String, S> target,
        final Map<String, S> source,
        final AstNode node
    ) {
        for (final var entry : source.entrySet()) {
            final S previous =
                target.putIfAbsent(entry.getKey(), entry.getValue());
            if (
                previous != null && (!previous.type()
                    .equals(entry.getValue().type())
                    || (previous instanceof VariableSymbol oldField
                        && entry.getValue() instanceof VariableSymbol newField
                        && oldField.mutability() != newField.mutability()))
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
        final Map<String, FunctionSymbol> methods = new LinkedHashMap<>();
        for (ClassType current = type; current != null; current =
            model.getSuperclass(current)) {
            for (final InterfaceType contract : model
                .getImplementedInterfaces(current)) {
                mergeContract(
                    fields,
                    model.getInterface(contract).fields(),
                    declaration
                );
                mergeContract(
                    methods,
                    model.getInterface(contract).methods(),
                    declaration
                );
            }
        }
        final Map<String, VariableSymbol> implementations =
            new LinkedHashMap<>();
        final List<Symbol> required = new ArrayList<>(fields.values());
        required.addAll(methods.values());
        for (final Symbol contract : required) {
            final ClassType owner =
                contract instanceof VariableSymbol
                    ? analyzer.classFieldOwner(type, contract.name())
                    : analyzer.memberOwner(type, contract.name());
            final Symbol implementation =
                contract instanceof FunctionSymbol
                    ? analyzer.classMethod(type, contract.name())
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
            if (
                model.getMemberVisibility(implementation) != Visibility.PUBLIC
            ) {
                throw new SemanticException(
                    implementation.range(),
                    "Interface member '%s' must be public",
                    contract.name()
                );
            }
            if (
                !contract.type().equals(implementation.type())
                    || (contract instanceof FunctionSymbol) != (implementation instanceof FunctionSymbol)
            ) {
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
        model.setInterfaceFields(type, implementations);
    }

}
