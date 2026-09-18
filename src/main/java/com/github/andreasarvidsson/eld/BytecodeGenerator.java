package com.github.andreasarvidsson.eld;

import static java.lang.classfile.ClassFile.*;
import static java.lang.classfile.Opcode.*;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassHierarchyResolver.ClassHierarchyInfo;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.*;
import com.github.andreasarvidsson.eld.semantic.ArrayType;
import com.github.andreasarvidsson.eld.semantic.TupleType;
import com.github.andreasarvidsson.eld.semantic.BuiltinFunctionSymbol;
import com.github.andreasarvidsson.eld.semantic.BuiltinFunctionType;
import com.github.andreasarvidsson.eld.semantic.BuiltinType;
import com.github.andreasarvidsson.eld.semantic.ClassType;
import com.github.andreasarvidsson.eld.semantic.InterfaceType;
import com.github.andreasarvidsson.eld.semantic.InterfaceContract;
import com.github.andreasarvidsson.eld.semantic.VariableSymbol;
import com.github.andreasarvidsson.eld.semantic.FunctionSymbol;
import com.github.andreasarvidsson.eld.semantic.FunctionType;
import com.github.andreasarvidsson.eld.semantic.SemanticModel;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzer;
import com.github.andreasarvidsson.eld.semantic.Symbol;
import com.github.andreasarvidsson.eld.semantic.Type;
import com.github.andreasarvidsson.eld.semantic.UnionType;
import com.github.andreasarvidsson.eld.semantic.JavaMethodSymbol;
import com.github.andreasarvidsson.eld.semantic.JavaTypes;

/** Generates a Java 21 module named Test and its declared classes. */
public final class BytecodeGenerator {
    private final String moduleName;
    private final String parentName;
    private final int previousItems;
    private final Program program;
    private final SemanticModel semanticModel;
    private final Map<String, String> classOwners;
    private @Nullable ClassBuilder currentWriter;
    private int nextLambda;
    private int nextObject;
    private final IdentityHashMap<ObjectExpression, String> objectNames =
        new IdentityHashMap<>();
    private final Map<String, byte[]> objectClasses = new LinkedHashMap<>();
    private final IdentityHashMap<ObjectExpression, ObjectInfo> objects =
        new IdentityHashMap<>();
    private final Map<String, InterfaceType> objectTypes =
        new LinkedHashMap<>();
    private final List<String> objectNameOrder = new ArrayList<>();

    private record ObjectInfo(
        String owner, List<Symbol> captures, boolean receiver,
        String constructorDescriptor
    ) {
    }

    public BytecodeGenerator(
        final Program program,
        final SemanticModel semanticModel
    ) {
        this(program, semanticModel, "Test", "java/lang/Object", 0);
    }

    BytecodeGenerator(
        final Program program,
        final SemanticModel semanticModel,
        final String moduleName,
        final String parentName,
        final int previousItems
    ) {
        this(
            program,
            semanticModel,
            moduleName,
            parentName,
            previousItems,
            Map.of()
        );
    }

    BytecodeGenerator(
        final Program program,
        final SemanticModel semanticModel,
        final String moduleName,
        final String parentName,
        final int previousItems,
        final Map<String, String> classOwners
    ) {
        this.classOwners = Map.copyOf(classOwners);
        this.program = program;
        this.semanticModel = semanticModel;
        this.moduleName = moduleName;
        this.parentName = parentName;
        this.previousItems = previousItems;
    }

    /** Generates a module without class declarations; otherwise use generateClasses(). */
    public byte[] generate() {
        if (
            program.items()
                .stream()
                .anyMatch(
                    item -> item instanceof ClassDeclaration
                        || item instanceof InterfaceDeclaration
                        || AstTraversal
                            .anyMatch(item, ObjectExpression.class::isInstance)
                )
        ) {
            throw new IllegalStateException(
                "This program declares classes; use generateClasses()"
            );
        }
        return generateModule();
    }

    /**
     * Returns class files keyed by JVM binary name, in module-first order.
     * Top-level class Foo is emitted as Test$Foo, a static member of Test.
     * Load or write every returned class file, not just the module.
     * Class fields and methods are instance members. Field initializers run in
     * source order before the constructor body; const fields are final.
     */
    public Map<String, byte[]> generateClasses() {
        nextLambda = 0;
        nextObject = 0;
        objectClasses.clear();
        objectTypes.clear();
        objects.clear();
        objectNames.clear();
        objectNameOrder.clear();
        for (final BlockItem item : program.items()
            .subList(previousItems, program.items().size())) {
            AstTraversal.walk(item, node -> {
                if (node instanceof ObjectExpression object) {
                    final String name = moduleName + "$$object" + nextObject++;
                    objectNames.put(object, name);
                    objectNameOrder.add(name);
                }
            });
        }
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(moduleName, generateModule());
        for (final InterfaceType type : semanticModel.getInterfaceTypes()) {
            if (
                type.name().startsWith("$spread")
                    && !classOwners.containsKey(type.name())
            ) {
                classes.put(
                    interfaceOwner(type),
                    classFile()
                        .build(classDesc(interfaceOwner(type)), writer -> {
                            final List<InnerClassInfo> innerClasses =
                                new ArrayList<>();
                            final List<ClassDesc> nestMembers =
                                new ArrayList<>();
                            writer.withVersion(JAVA_21_VERSION, 0);
                            writer.withFlags(
                                ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT
                            );
                            writer
                                .withSuperclass(classDesc("java/lang/Object"));

                            for (final VariableSymbol field : semanticModel
                                .getInterface(type)
                                .fields()
                                .values()) {
                                declareMethod(
                                    writer,
                                    ACC_PUBLIC | ACC_ABSTRACT,
                                    "$get$" + field.name(),
                                    "()" + descriptor(field.type()),
                                    null
                                );
                                if (field.mutability() == Mutability.VAR) {
                                    declareMethod(
                                        writer,
                                        ACC_PUBLIC | ACC_ABSTRACT,
                                        "$set$" + field.name(),
                                        "(" + descriptor(field.type()) + ")V",
                                        null
                                    );
                                }
                            }
                            for (final FunctionSymbol function : semanticModel
                                .getInterface(type)
                                .methods()
                                .values()) {
                                declareMethod(
                                    writer,
                                    ACC_PUBLIC | ACC_ABSTRACT,
                                    function.name(),
                                    methodDescriptor(function.type()),
                                    null
                                );
                                if (hasDefaultParameters(function)) {
                                    declareMethod(
                                        writer,
                                        ACC_PUBLIC | ACC_ABSTRACT
                                            | ACC_SYNTHETIC,
                                        function.name(),
                                        defaultDescriptor(function.type()),
                                        null
                                    );
                                }
                            }

                            if (!innerClasses.isEmpty()) {
                                writer.with(
                                    InnerClassesAttribute.of(innerClasses)
                                );
                            }
                            if (!nestMembers.isEmpty()) {
                                writer.with(
                                    NestMembersAttribute.ofSymbols(nestMembers)
                                );
                            }
                        })
                );
            }
        }

        for (final BlockItem item : program.items()
            .subList(previousItems, program.items().size())) {
            if (item instanceof InterfaceDeclaration contract) {
                classes.put(
                    interfaceOwner(
                        (InterfaceType) semanticModel.getSymbol(contract.name())
                            .type()
                    ),
                    generateInterface(contract)
                );
            }
            if (item instanceof ClassDeclaration declaration) {
                final String name = className(declaration);
                if (
                    classes
                        .putIfAbsent(name, generateClass(declaration)) != null
                ) {
                    throw unsupported(
                        declaration,
                        "Duplicate class " + declaration.name().name()
                    );
                }
            }
        }
        classes.putAll(objectClasses);
        return Collections.unmodifiableMap(classes);
    }

    private ClassFile classFile() {
        final ClassHierarchyResolver generated = type -> {
            final String owner = internalName(type);
            if (owner.equals(moduleName)) {
                return ClassHierarchyInfo.ofClass(classDesc(parentName));
            }
            final ClassType generatedClass = generatedClassType(owner);
            if (generatedClass != null) {
                final ClassType superclass =
                    semanticModel.getSuperclass(generatedClass);
                return ClassHierarchyInfo.ofClass(
                    classDesc(
                        superclass == null
                            ? "java/lang/Object"
                            : classOwner(superclass)
                    )
                );
            }
            for (final InterfaceType contract : semanticModel
                .getInterfaceTypes()) {
                if (
                    interfaceOwner(contract).equals(owner)
                        && contract.javaClass() == null
                ) {
                    return ClassHierarchyInfo.ofInterface();
                }
            }
            if (
                objectNameOrder.contains(owner)
                    || owner.startsWith(moduleName + "$")
            ) {
                return ClassHierarchyInfo
                    .ofClass(ClassDesc.of("java.lang.Object"));
            }
            return null;
        };
        return ClassFile.of(
            ClassFile.ClassHierarchyResolverOption.of(
                generated.orElse(ClassHierarchyResolver.defaultResolver())
                    .cached()
            )
        );
    }

    private static String internalName(final ClassDesc type) {
        final String descriptor = type.descriptorString();
        return descriptor.startsWith("[")
            ? descriptor
            : descriptor.substring(1, descriptor.length() - 1);
    }

    private static ClassDesc classDesc(final String owner) {
        return owner.startsWith("[")
            ? ClassDesc.ofDescriptor(owner)
            : ClassDesc.ofInternalName(owner);
    }

    private static MethodTypeDesc javaMethodDescriptor(
        final java.lang.reflect.Method method
    ) {
        return MethodTypeDesc.of(
            method.getReturnType().describeConstable().orElseThrow(),
            Arrays.stream(method.getParameterTypes())
                .map(type -> type.describeConstable().orElseThrow())
                .toArray(ClassDesc[]::new)
        );
    }

    private static void generateClassSignature(
        final ClassBuilder writer,
        final @Nullable String signature
    ) {
        if (signature != null) {
            writer.with(
                SignatureAttribute
                    .of(writer.constantPool().utf8Entry(signature))
            );
        }
    }

    private static void declareMethod(
        final ClassBuilder writer,
        final int access,
        final String name,
        final String descriptor,
        final @Nullable String signature
    ) {
        writer.withMethod(
            name,
            MethodTypeDesc.ofDescriptor(descriptor),
            access,
            method -> {
                if (signature != null) {
                    method.with(
                        SignatureAttribute
                            .of(method.constantPool().utf8Entry(signature))
                    );
                }
            }
        );
    }

    private static void generateMethod(
        final ClassBuilder writer,
        final int access,
        final String name,
        final String descriptor,
        final @Nullable String signature,
        final Consumer<CodeBuilder> body
    ) {
        writer.withMethod(
            name,
            MethodTypeDesc.ofDescriptor(descriptor),
            access,
            method -> {
                if (signature != null) {
                    method.with(
                        SignatureAttribute
                            .of(method.constantPool().utf8Entry(signature))
                    );
                }
                method.withCode(body);
            }
        );
    }

    private static void generateField(
        final ClassBuilder writer,
        final int access,
        final String name,
        final String descriptor,
        final @Nullable String signature,
        final @Nullable Object value
    ) {
        writer.withField(name, ClassDesc.ofDescriptor(descriptor), field -> {
            field.withFlags(access);
            if (signature != null) {
                field.with(
                    SignatureAttribute
                        .of(field.constantPool().utf8Entry(signature))
                );
            }
            if (value != null) {
                field.with(ConstantValueAttribute.of((ConstantDesc) value));
            }
        });
    }

    private String className(final ClassDeclaration declaration) {
        return moduleName + "$" + declaration.name().name();
    }

    private @Nullable ClassType generatedClassType(final String owner) {
        for (final ClassType type : semanticModel.getClassTypes()) {
            if (classOwner(type).equals(owner)) {
                return type;
            }
        }
        return null;
    }

    private byte[] generateInterface(final InterfaceDeclaration declaration) {
        final InterfaceType type =
            (InterfaceType) semanticModel.getSymbol(declaration.name()).type();
        final String owner = interfaceOwner(type);
        return classFile().build(classDesc(owner), writer -> {
            final List<InnerClassInfo> innerClasses = new ArrayList<>();
            final List<ClassDesc> nestMembers = new ArrayList<>();
            writer.withVersion(JAVA_21_VERSION, 0);
            writer.withFlags(ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT);
            writer.withSuperclass(classDesc("java/lang/Object"));
            generateClassSignature(
                writer,
                classSignature(
                    "java/lang/Object",
                    semanticModel.getInterface(type).superInterfaces()
                )
            );
            writer.withInterfaceSymbols(
                Arrays
                    .stream(
                        semanticModel.getInterface(type)
                            .superInterfaces()
                            .stream()
                            .map(this::interfaceOwner)
                            .toArray(String[]::new)
                    )
                    .map(BytecodeGenerator::classDesc)
                    .toList()
            );

            currentWriter = writer;

            writer.with(NestHostAttribute.of(classDesc(moduleName)));
            innerClasses.add(
                InnerClassInfo.of(
                    classDesc(owner),
                    Optional.of(classDesc(moduleName)),
                    Optional.of(declaration.name().name()),
                    ACC_PUBLIC | ACC_STATIC | ACC_INTERFACE | ACC_ABSTRACT
                )
            );
            final IdentityHashMap<Symbol, String> globals =
                new IdentityHashMap<>();
            for (final BlockItem item : program.items()) {
                if (item instanceof VariableDeclaration variable) {
                    final Symbol symbol =
                        semanticModel.getSymbol(variable.name());
                    globals.put(symbol, symbol.name());
                }
            }
            for (final var member : declaration.members()) {
                if (member instanceof UninitializedVariableDeclaration field) {
                    declareMethod(
                        writer,
                        ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC,
                        "$get$" + field.name().name(),
                        "()" + descriptor(
                            semanticModel.getSymbol(field.name()).type()
                        ),
                        null
                    );
                    if (field.mutability() == Mutability.VAR) {
                        declareMethod(
                            writer,
                            ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC,
                            "$set$" + field.name().name(),
                            "(" + descriptor(
                                semanticModel.getSymbol(field.name()).type()
                            ) + ")V",
                            null
                        );
                    }
                }
                else if (member instanceof InterfaceMethodDeclaration method) {
                    final FunctionType signature =
                        (FunctionType) semanticModel.getSymbol(method.name())
                            .type();
                    declareMethod(
                        writer,
                        ACC_PUBLIC | ACC_ABSTRACT,
                        method.name().name(),
                        methodDescriptor(signature),
                        null
                    );
                    generateDefaultOverload(
                        writer,
                        method.name().name(),
                        signature,
                        method.parameters(),
                        globals,
                        new InstanceContext(owner, new IdentityHashMap<>()),
                        Visibility.PUBLIC
                    );
                }
            }

            if (!innerClasses.isEmpty()) {
                writer.with(InnerClassesAttribute.of(innerClasses));
            }
            if (!nestMembers.isEmpty()) {
                writer.with(NestMembersAttribute.ofSymbols(nestMembers));
            }
        });
    }

    private void generateGetter(
        final ClassBuilder writer,
        final String name,
        final String fieldOwner,
        final Type type
    ) {
        generateMethod(
            writer,
            ACC_PUBLIC | ACC_SYNTHETIC,
            "$get$" + name,
            "()" + descriptor(type),
            null,
            getter -> {

                getter.aload(0);
                getter.fieldAccess(
                    GETFIELD,
                    classDesc(fieldOwner),
                    name,
                    ClassDesc.ofDescriptor(descriptor(type))
                );
                getter.with(simpleInstruction(returnOpcode(type)));

            }
        );
    }

    private void generateSetter(
        final ClassBuilder writer,
        final String name,
        final String fieldOwner,
        final Type type
    ) {
        generateMethod(
            writer,
            ACC_PUBLIC | ACC_SYNTHETIC,
            "$set$" + name,
            "(" + descriptor(type) + ")V",
            null,
            setter -> {

                setter.aload(0);
                setter.with(localInstruction(loadOpcode(type), 1));
                setter.fieldAccess(
                    PUTFIELD,
                    classDesc(fieldOwner),
                    name,
                    ClassDesc.ofDescriptor(descriptor(type))
                );
                setter.return_();

            }
        );
    }

    private boolean mutableInterfaceField(
        final ClassType type,
        final String name
    ) {
        for (ClassType current = type; current != null; current =
            semanticModel.getSuperclass(current)) {
            for (final InterfaceType contract : semanticModel
                .getImplementedInterfaces(current)) {
                final VariableSymbol field =
                    semanticModel.getInterface(contract).fields().get(name);
                if (field != null && field.mutability() == Mutability.VAR) {
                    return true;
                }
            }
        }
        return false;
    }

    private byte[] generateClass(final ClassDeclaration declaration) {
        final String name = className(declaration);
        final ClassType classType =
            (ClassType) semanticModel.getSymbol(declaration.name()).type();
        final ClassType superclass = semanticModel.getSuperclass(classType);
        final String superclassOwner =
            superclass == null ? "java/lang/Object" : classOwner(superclass);
        return classFile().build(classDesc(name), writer -> {
            final List<InnerClassInfo> innerClasses = new ArrayList<>();
            final List<ClassDesc> nestMembers = new ArrayList<>();
            writer.withVersion(JAVA_21_VERSION, 0);
            writer.withFlags(ACC_PUBLIC | ACC_SUPER);
            writer.withSuperclass(classDesc(superclassOwner));
            generateClassSignature(
                writer,
                classSignature(
                    superclassOwner,
                    semanticModel.getImplementedInterfaces(classType)
                )
            );
            writer.withInterfaceSymbols(
                Arrays
                    .stream(
                        semanticModel.getImplementedInterfaces(classType)
                            .stream()
                            .map(BytecodeGenerator.this::interfaceOwner)
                            .toArray(String[]::new)
                    )
                    .map(BytecodeGenerator::classDesc)
                    .toList()
            );

            currentWriter = writer;

            writer.with(NestHostAttribute.of(classDesc(moduleName)));
            innerClasses.add(
                InnerClassInfo.of(
                    classDesc(name),
                    Optional.of(classDesc(moduleName)),
                    Optional.of(declaration.name().name()),
                    ACC_PUBLIC | ACC_STATIC
                )
            );
            final IdentityHashMap<Symbol, String> globals =
                new IdentityHashMap<>();
            for (final BlockItem item : program.items()) {
                if (item instanceof VariableDeclaration variable) {
                    final Symbol symbol =
                        semanticModel.getSymbol(variable.name());
                    globals.put(symbol, symbol.name());
                }
            }
            final IdentityHashMap<Symbol, String> members =
                new IdentityHashMap<>();
            for (final MemberDeclaration memberDeclaration : declaration
                .members()) {
                final Declaration member = memberDeclaration.declaration();
                if (member instanceof VariableDeclaration variable) {
                    final Symbol symbol =
                        semanticModel.getSymbol(variable.name());
                    members.put(symbol, symbol.name());
                    // Instance constants must be assigned by each constructor.
                    generateField(
                        writer,
                        visibilityAccess(memberDeclaration.visibility())
                            | (variable.mutability() == Mutability.CONST
                                ? ACC_FINAL
                                : 0),
                        symbol.name(),
                        descriptor(symbol.type()),
                        fieldSignature(symbol.type()),
                        null
                    );
                }
                else if (member instanceof FunctionDeclaration function) {
                    final Symbol symbol =
                        semanticModel.getSymbol(function.name());
                    members.put(symbol, symbol.name());
                }
                else if (
                    member instanceof UninitializedVariableDeclaration field
                ) {
                    final Symbol symbol = semanticModel.getSymbol(field.name());
                    members.put(symbol, symbol.name());
                    generateField(
                        writer,
                        visibilityAccess(memberDeclaration.visibility())
                            | (field.mutability() == Mutability.CONST
                                ? ACC_FINAL
                                : 0),
                        symbol.name(),
                        descriptor(symbol.type()),
                        fieldSignature(symbol.type()),
                        null
                    );
                }
                else if (member instanceof ClassDeclaration) {
                    throw unsupported(
                        member,
                        "Nested class generation is not supported yet"
                    );
                }
            }
            for (final var entry : semanticModel.getInterfaceFields(classType)
                .entrySet()) {
                final VariableSymbol field = entry.getValue();
                generateGetter(
                    writer,
                    entry.getKey(),
                    classOwner(semanticModel.getClassMemberOwner(field)),
                    field.type()
                );
                if (mutableInterfaceField(classType, entry.getKey())) {
                    generateSetter(
                        writer,
                        entry.getKey(),
                        classOwner(semanticModel.getClassMemberOwner(field)),
                        field.type()
                    );
                }
            }
            final InstanceContext instance = new InstanceContext(name, members);
            final ConstructorDeclaration declarationConstructor =
                declaration.members()
                    .stream()
                    .map(MemberDeclaration::declaration)
                    .filter(ConstructorDeclaration.class::isInstance)
                    .map(ConstructorDeclaration.class::cast)
                    .findFirst()
                    .orElse(null);
            final FunctionType constructorType =
                semanticModel.getConstructor(
                    (ClassType) semanticModel.getSymbol(declaration.name())
                        .type()
                );
            generateMethod(
                writer,
                visibilityAccess(
                    semanticModel.getConstructorVisibility(
                        (ClassType) semanticModel.getSymbol(declaration.name())
                            .type()
                    )
                ),
                "<init>",
                methodDescriptor(constructorType),
                null,
                constructor -> {
                    final MethodGenerator initializer =
                        new MethodGenerator(
                            constructor,
                            globals,
                            BuiltinType.VOID,
                            instance
                        );

                    if (declarationConstructor != null) {
                        for (final FunctionParameter parameter : declarationConstructor
                            .parameters()) {
                            initializer.local(
                                semanticModel.getSymbol(parameter.name())
                            );
                        }
                    }
                    initializer.constructorSuperclass = superclass;
                    initializer.constructorFields =
                        declaration.members()
                            .stream()
                            .map(MemberDeclaration::declaration)
                            .filter(VariableDeclaration.class::isInstance)
                            .map(VariableDeclaration.class::cast)
                            .toList();
                    final boolean explicitSuper =
                        declarationConstructor != null
                            && declarationConstructor.hasExplicitSuperCall();
                    if (!explicitSuper) {
                        initializer.initializeBase(List.of());
                    }
                    final boolean reachable =
                        declarationConstructor == null
                            || initializer.block(declarationConstructor.body());
                    initializer.finish(reachable);
                }
            );
            if (declarationConstructor != null) {
                generateDefaultOverload(
                    writer,
                    "<init>",
                    constructorType,
                    declarationConstructor.parameters(),
                    globals,
                    instance,
                    semanticModel.getConstructorVisibility(
                        (ClassType) semanticModel.getSymbol(declaration.name())
                            .type()
                    )
                );
            }
            for (final MemberDeclaration memberDeclaration : declaration
                .members()) {
                final Declaration member = memberDeclaration.declaration();
                if (member instanceof FunctionDeclaration function) {
                    generateFunction(writer, function, globals, instance);
                }
            }
            generateJavaBridges(
                writer,
                name,
                semanticModel.getImplementedInterfaces(classType)
            );

            if (!innerClasses.isEmpty()) {
                writer.with(InnerClassesAttribute.of(innerClasses));
            }
            if (!nestMembers.isEmpty()) {
                writer.with(NestMembersAttribute.ofSymbols(nestMembers));
            }
        });
    }

    private record InstanceContext(
        String owner, IdentityHashMap<Symbol, String> members
    ) {
    }

    private void generateFunction(
        final ClassBuilder writer,
        final FunctionDeclaration function,
        final IdentityHashMap<Symbol, String> globals,
        final @Nullable InstanceContext instance
    ) {
        final FunctionSymbol symbol =
            (FunctionSymbol) semanticModel.getSymbol(function.name());
        generateMethod(
            writer,
            visibilityAccess(
                instance == null
                    ? Visibility.PUBLIC
                    : semanticModel.getMemberVisibility(symbol)
            ) | (instance == null ? ACC_STATIC : 0),
            symbol.name(),
            methodDescriptor(symbol.type()),
            methodSignature(symbol.type()),
            method -> {
                final MethodGenerator generator =
                    new MethodGenerator(
                        method,
                        globals,
                        symbol.type().returnType(),
                        instance
                    );

                for (final FunctionParameter parameter : function
                    .parameters()) {
                    generator.local(semanticModel.getSymbol(parameter.name()));
                }
                generator.finish(generator.block(function.body()));
            }
        );
        generateDefaultOverload(
            writer,
            symbol.name(),
            symbol.type(),
            function.parameters(),
            globals,
            instance,
            instance == null
                ? Visibility.PUBLIC
                : semanticModel.getMemberVisibility(symbol)
        );
    }

    private String defaultDescriptor(final FunctionType type) {
        return methodDescriptor(type).replace(")", "[Z)");
    }

    private boolean hasDefaultParameters(final FunctionSymbol function) {
        return semanticModel.getFunctionParameters(function)
            .stream()
            .map(semanticModel::getParameterDetails)
            .anyMatch(FunctionParameter::omittable);
    }

    private static int visibilityAccess(final Visibility visibility) {
        return switch (visibility) {
            case PRIVATE -> ACC_PRIVATE;
            case PUBLIC -> ACC_PUBLIC;
            case PROTECTED -> ACC_PROTECTED;
        };
    }

    private void generateDefaultOverload(
        final ClassBuilder writer,
        final String name,
        final FunctionType type,
        final List<FunctionParameter> parameters,
        final IdentityHashMap<Symbol, String> globals,
        final @Nullable InstanceContext instance,
        final Visibility visibility
    ) {
        if (parameters.stream().noneMatch(FunctionParameter::omittable)) {
            return;
        }
        generateMethod(
            writer,
            visibilityAccess(visibility) | ACC_SYNTHETIC
                | (instance == null ? ACC_STATIC : 0),
            name,
            defaultDescriptor(type),
            null,
            method -> {
                final MethodGenerator generator =
                    new MethodGenerator(
                        method,
                        globals,
                        type.returnType(),
                        instance
                    );
                generator.beforeBaseInitialization = name.equals("<init>");

                for (final FunctionParameter parameter : parameters) {
                    generator.local(semanticModel.getSymbol(parameter.name()));
                }
                final int mask = generator.nextLocal++;
                for (int i = 0; i < parameters.size(); i++) {
                    final FunctionParameter parameter = parameters.get(i);
                    if (!parameter.omittable()) {
                        continue;
                    }
                    final Label supplied = method.newLabel();
                    method.aload(mask);
                    method.ldc(i);
                    method.baload();
                    method.branch(IFEQ, supplied);
                    if (parameter.defaultValue() != null) {
                        generator.expression(parameter.defaultValue());
                    }
                    else {
                        method.aconst_null();
                    }
                    method.with(
                        localInstruction(
                            storeOpcode(type.parameterTypes().get(i)),
                            generator.local(
                                semanticModel.getSymbol(parameter.name())
                            )
                        )
                    );
                    method.labelBinding(supplied);
                }
                if (instance != null) {
                    method.aload(0);
                }
                for (final FunctionParameter parameter : parameters) {
                    final Symbol symbol =
                        semanticModel.getSymbol(parameter.name());
                    method.with(
                        localInstruction(
                            loadOpcode(symbol.type()),
                            generator.local(symbol)
                        )
                    );
                }
                method.invoke(
                    name.equals("<init>")
                        ? INVOKESPECIAL
                        : instance == null
                            ? INVOKESTATIC
                            : interfaceOwnerName(instance.owner())
                                ? INVOKEINTERFACE
                                : INVOKEVIRTUAL,
                    classDesc(instance == null ? moduleName : instance.owner()),
                    name,
                    MethodTypeDesc.ofDescriptor(methodDescriptor(type)),
                    instance != null && interfaceOwnerName(instance.owner())
                );
                method.with(simpleInstruction(returnOpcode(type.returnType())));

            }
        );
    }

    private byte[] generateModule() {
        return classFile().build(classDesc(moduleName), writer -> {
            final List<InnerClassInfo> innerClasses = new ArrayList<>();
            final List<ClassDesc> nestMembers = new ArrayList<>();
            writer.withVersion(JAVA_21_VERSION, 0);
            writer.withFlags(
                ACC_PUBLIC | ACC_SUPER
                    | (moduleName.equals("Test") ? ACC_FINAL : 0)
            );
            writer.withSuperclass(classDesc(parentName));

            currentWriter = writer;
            for (final String objectName : objectNameOrder) {
                nestMembers.add(classDesc(objectName));
            }

            final IdentityHashMap<Symbol, String> globals =
                new IdentityHashMap<>();
            final List<BlockItem> initializers = new ArrayList<>();
            for (final BlockItem item : program.items()
                .subList(0, previousItems)) {
                if (item instanceof VariableDeclaration variable) {
                    final Symbol symbol =
                        semanticModel.getSymbol(variable.name());
                    globals.put(symbol, symbol.name());
                }
            }
            for (final BlockItem item : program.items()
                .subList(previousItems, program.items().size())) {
                if (item instanceof VariableDeclaration variable) {
                    final Symbol symbol =
                        semanticModel.getSymbol(variable.name());
                    final Expression initializer = variable.initializer();
                    final Object constantValue =
                        variable.mutability() == Mutability.CONST
                            ? constantValue(initializer)
                            : null;
                    if (constantValue == null) {
                        initializers.add(item);
                    }
                    globals.put(symbol, symbol.name());
                    generateField(
                        writer,
                        ACC_PUBLIC | ACC_STATIC
                            | (moduleName.equals("Test")
                                && variable.mutability() == Mutability.CONST
                                    ? ACC_FINAL
                                    : 0),
                        symbol.name(),
                        descriptor(symbol.type()),
                        fieldSignature(symbol.type()),
                        constantValue
                    );
                }
                else if (item instanceof ClassDeclaration declaration) {
                    final String name = className(declaration);
                    nestMembers.add(classDesc(name));
                    innerClasses.add(
                        InnerClassInfo.of(
                            classDesc(name),
                            Optional.of(classDesc(moduleName)),
                            Optional.of(declaration.name().name()),
                            ACC_PUBLIC | ACC_STATIC
                        )
                    );
                }
                else if (item instanceof InterfaceDeclaration contract) {
                    final String name =
                        interfaceOwner(
                            (InterfaceType) semanticModel
                                .getSymbol(contract.name())
                                .type()
                        );
                    nestMembers.add(classDesc(name));
                    innerClasses.add(
                        InnerClassInfo.of(
                            classDesc(name),
                            Optional.of(classDesc(moduleName)),
                            Optional.of(contract.name().name()),
                            ACC_PUBLIC | ACC_STATIC | ACC_INTERFACE
                                | ACC_ABSTRACT
                        )
                    );
                }
                else if (!(item instanceof FunctionDeclaration)) {
                    initializers.add(item);
                }
            }
            for (final BlockItem item : program.items()
                .subList(previousItems, program.items().size())) {
                if (item instanceof FunctionDeclaration function) {
                    generateFunction(writer, function, globals, null);
                }
            }
            if (!initializers.isEmpty() || !moduleName.equals("Test")) {
                generateMethod(
                    writer,
                    moduleName.equals("Test")
                        ? ACC_STATIC
                        : ACC_PUBLIC | ACC_STATIC,
                    moduleName.equals("Test") ? "<clinit>" : "$eval",
                    "()V",
                    null,
                    method -> {
                        final MethodGenerator generator =
                            new MethodGenerator(
                                method,
                                globals,
                                BuiltinType.VOID
                            );

                        boolean reachable = true;
                        for (final BlockItem item : initializers) {
                            if (!reachable) {
                                break;
                            }
                            if (
                                !moduleName.equals(
                                    "Test"
                                ) && item instanceof ExpressionStatement statement
                                    && semanticModel.getEffectiveType(
                                        statement.expression()
                                    ) != BuiltinType.VOID
                            ) {
                                method.fieldAccess(
                                    GETSTATIC,
                                    classDesc("java/lang/System"),
                                    "out",
                                    ClassDesc
                                        .ofDescriptor("Ljava/io/PrintStream;")
                                );
                                generator.expression(statement.expression());
                                final Type type =
                                    semanticModel.getEffectiveType(
                                        statement.expression()
                                    );
                                final String argument =
                                    printArgumentDescriptor(type);
                                method.invoke(
                                    INVOKEVIRTUAL,
                                    classDesc("java/io/PrintStream"),
                                    "println",
                                    MethodTypeDesc
                                        .ofDescriptor("(" + argument + ")V"),
                                    false
                                );
                                continue;
                            }
                            reachable = generator.item(item);
                        }
                        generator.finish(reachable);
                    }
                );
            }

            if (!innerClasses.isEmpty()) {
                writer.with(InnerClassesAttribute.of(innerClasses));
            }
            if (!nestMembers.isEmpty()) {
                writer.with(NestMembersAttribute.ofSymbols(nestMembers));
            }
        });
    }

    // null means this expression must be evaluated at runtime. In particular,
    // JVM ConstantValue cannot represent null, arrays, or function references.
    private @Nullable Object constantValue(final Expression expression) {
        if (
            semanticModel.getEffectiveType(expression) instanceof UnionType
                || semanticModel
                    .getEffectiveType(expression) instanceof InterfaceType
                || semanticModel.getEffectiveType(expression) == BuiltinType.ANY
        ) {
            return null;
        }
        final Object value = switch (expression) {
            case LiteralExpression literal -> switch (literal.kind()) {
                case INT -> integerConstant(literal);
                case FLOAT -> floatingConstant(literal);
                case BOOL -> Boolean.parseBoolean(literal.text()) ? 1 : 0;
                case CHAR -> (int) decodeChar(literal.text());
                case STRING -> decodeString(literal.text());
                case RAW_STRING ->
                    literal.text().substring(1, literal.text().length() - 1);
                case NULL -> null;
            };
            case GroupingExpression grouping ->
                constantValue(grouping.expression());
            case UnaryExpression unary -> constantUnary(unary);
            case BinaryExpression binary -> constantBinary(binary);
            default -> null;
        };
        if (value instanceof Number number) {
            final Type type = semanticModel.getEffectiveType(expression);
            if (type == BuiltinType.I8) {
                return (int) number.byteValue();
            }
            if (type == BuiltinType.I16) {
                return (int) number.shortValue();
            }
            if (type == BuiltinType.I64) {
                return number.longValue();
            }
            if (type == BuiltinType.F32) {
                return number.floatValue();
            }
            if (type == BuiltinType.F64) {
                return number.doubleValue();
            }
        }
        return value;
    }

    private Object integerConstant(final LiteralExpression literal) {
        final String text = literal.text().replace("_", "");
        if (semanticModel.getExpressionType(literal) == BuiltinType.I64) {
            return Long.parseLong(text);
        }
        return Integer.parseInt(text);
    }

    private Object floatingConstant(final LiteralExpression literal) {
        final String text = literal.text().replace("_", "");
        if (semanticModel.getExpressionType(literal) == BuiltinType.F64) {
            return Double.parseDouble(text);
        }
        return Float.parseFloat(text);
    }

    private @Nullable Object constantUnary(final UnaryExpression unary) {
        final java.math.BigInteger value =
            SemanticAnalyzer.integerLiteral(unary);
        if (value != null) {
            if (semanticModel.getExpressionType(unary) == BuiltinType.I64) {
                return value.longValueExact();
            }
            return value.intValueExact();
        }
        final Object operand = constantValue(unary.operand());
        return switch (unary.operator()) {
            case PLUS -> operand;
            case MINUS -> {
                if (operand instanceof Integer integer) {
                    yield -integer;
                }
                if (operand instanceof Long integer) {
                    yield -integer;
                }
                if (operand instanceof Double floating) {
                    yield -floating;
                }
                if (operand instanceof Float floating) {
                    yield -floating;
                }
                yield null;
            }
            case NOT -> operand instanceof Integer integer
                ? (integer == 0 ? 1 : 0)
                : null;
            default -> null;
        };
    }

    private @Nullable Object constantBinary(final BinaryExpression binary) {
        final Object left = constantValue(binary.left());
        final Object right = constantValue(binary.right());
        if (left instanceof Integer a && right instanceof Integer b) {
            return switch (binary.operator()) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                // Preserve runtime exceptions and initialization order.
                case DIVIDE -> b == 0 ? null : a / b;
                case MODULO -> b == 0 ? null : a % b;
                case EQUAL -> a.intValue() == b.intValue() ? 1 : 0;
                case NOT_EQUAL -> a.intValue() != b.intValue() ? 1 : 0;
                case LESS -> a < b ? 1 : 0;
                case LESS_EQUAL -> a <= b ? 1 : 0;
                case GREATER -> a > b ? 1 : 0;
                case GREATER_EQUAL -> a >= b ? 1 : 0;
                case AND -> a != 0 && b != 0 ? 1 : 0;
                case OR -> a != 0 || b != 0 ? 1 : 0;
            };
        }
        if (left instanceof Float a && right instanceof Float b) {
            // Round each AST operation as JVM float arithmetic does; computing
            // in double and narrowing only the final result changes the value.
            return switch (binary.operator()) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> a / b;
                case MODULO -> a % b;
                case EQUAL -> a.floatValue() == b.floatValue() ? 1 : 0;
                case NOT_EQUAL -> a.floatValue() != b.floatValue() ? 1 : 0;
                case LESS -> a < b ? 1 : 0;
                case LESS_EQUAL -> a <= b ? 1 : 0;
                case GREATER -> a > b ? 1 : 0;
                case GREATER_EQUAL -> a >= b ? 1 : 0;
                default -> null;
            };
        }
        if (left instanceof Double a && right instanceof Double b) {
            return switch (binary.operator()) {
                case ADD -> a + b;
                case SUBTRACT -> a - b;
                case MULTIPLY -> a * b;
                case DIVIDE -> a / b;
                case MODULO -> a % b;
                case EQUAL -> a.doubleValue() == b.doubleValue() ? 1 : 0;
                case NOT_EQUAL -> a.doubleValue() != b.doubleValue() ? 1 : 0;
                case LESS -> a < b ? 1 : 0;
                case LESS_EQUAL -> a <= b ? 1 : 0;
                case GREATER -> a > b ? 1 : 0;
                case GREATER_EQUAL -> a >= b ? 1 : 0;
                default -> null;
            };
        }
        if (left instanceof String a && right instanceof String b) {
            return switch (binary.operator()) {
                case ADD -> a + b;
                case EQUAL -> a.equals(b) ? 1 : 0;
                case NOT_EQUAL -> a.equals(b) ? 0 : 1;
                default -> null;
            };
        }
        return null;
    }

    private static char decodeChar(final String text) {
        return text.substring(1, text.length() - 1)
            .translateEscapes()
            .charAt(0);
    }

    private static String decodeString(final String text) {
        // Decode supported escapes while preserving other backslash sequences.
        final StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (
                c == '\\' && i + 1 < text.length() - 1
                    && "btnfr0'\"\\".indexOf(text.charAt(i + 1)) >= 0
            ) {
                c = switch (text.charAt(++i)) {
                    case 'b' -> '\b';
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'f' -> '\f';
                    case 'r' -> '\r';
                    case '0' -> '\0';
                    default -> text.charAt(i);
                };
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    private String classOwner(final ClassType type) {
        return classOwners
            .getOrDefault(type.name(), moduleName + "$" + type.name());
    }

    private String interfaceOwner(final InterfaceType type) {
        if (type.javaClass() != null) {
            return type.javaClass().getName().replace('.', '/');
        }
        return classOwners
            .getOrDefault(type.name(), moduleName + "$" + type.name());
    }

    private String typeOwner(final Type type) {
        return type instanceof ClassType cls
            ? classOwner(cls)
            : interfaceOwner((InterfaceType) type);
    }

    private @Nullable Type generatedReferenceType(final String owner) {
        final ClassType cls = generatedClassType(owner);
        if (cls != null) {
            return cls;
        }
        if (objectTypes.containsKey(owner)) {
            return objectTypes.get(owner);
        }
        for (final InterfaceType type : semanticModel.getInterfaceTypes()) {
            if (interfaceOwner(type).equals(owner)) {
                return type;
            }
        }
        return null;
    }

    private boolean interfaceOwnerName(final String owner) {
        return generatedReferenceType(owner) instanceof InterfaceType
            && !objectTypes.containsKey(owner);
    }

    private String descriptor(final Type type) {
        return switch (type) {
            case BuiltinType builtin -> switch (builtin) {
                case I8 -> "B";
                case I16 -> "S";
                case I32 -> "I";
                case I64 -> "J";
                case F32 -> "F";
                case F64 -> "D";
                case BOOL -> "Z";
                case CHAR -> "C";
                case STRING -> "Ljava/lang/String;";
                case NULL, ANY -> "Ljava/lang/Object;";
                case VOID -> "V";
            };
            case ArrayType array ->
                RuntimeAbi.array(array.elementType()).descriptor;
            case TupleType ignored ->
                "Lcom/github/andreasarvidsson/eld/runtime/EldTuple;";
            case UnionType union -> unionDescriptor(union);
            case FunctionType ignored -> "Ljava/lang/invoke/MethodHandle;";
            case BuiltinFunctionType ignored -> "Ljava/io/PrintStream;";
            case ClassType classType -> "L" + classOwner(classType) + ";";
            case InterfaceType contract -> "L" + interfaceOwner(contract) + ";";
        };
    }

    private String unionDescriptor(final UnionType union) {
        final List<Type> members =
            union.memberTypes()
                .stream()
                .filter(member -> member != BuiltinType.NULL)
                .distinct()
                .toList();
        if (members.size() != 1) {
            return "Ljava/lang/Object;";
        }
        return boxedDescriptor(members.getFirst());
    }

    private String boxedDescriptor(final Type type) {
        final String boxed = boxedOwner(type);
        return boxed == null ? descriptor(type) : "L" + boxed + ";";
    }

    private String genericSignature(final Type type) {
        if (
            type instanceof InterfaceType contract
                && !contract.typeArguments().isEmpty()
        ) {
            return "L" + interfaceOwner(contract)
                + contract.typeArguments()
                    .stream()
                    .map(this::genericSignature)
                    .collect(Collectors.joining("", "<", ">;"));
        }
        return boxedDescriptor(type);
    }

    private @Nullable String classSignature(
        final String superclass,
        final List<InterfaceType> interfaces
    ) {
        if (
            interfaces.stream()
                .noneMatch(type -> !type.typeArguments().isEmpty())
        ) {
            return null;
        }
        return "L" + superclass + ";"
            + interfaces.stream()
                .map(this::genericSignature)
                .collect(Collectors.joining());
    }

    private @Nullable String fieldSignature(final Type type) {
        if (
            type instanceof InterfaceType contract
                && !contract.typeArguments().isEmpty()
        ) {
            return genericSignature(type);
        }
        if (type instanceof UnionType union) {
            final List<Type> members =
                union.memberTypes()
                    .stream()
                    .filter(member -> member != BuiltinType.NULL)
                    .toList();
            if (members.size() == 1) {
                return fieldSignature(members.getFirst());
            }
        }
        return null;
    }

    private @Nullable String methodSignature(final FunctionType type) {
        if (
            fieldSignature(type.returnType()) == null && type.parameterTypes()
                .stream()
                .allMatch(parameter -> fieldSignature(parameter) == null)
        ) {
            return null;
        }
        return "("
            + type.parameterTypes()
                .stream()
                .map(
                    parameter -> Objects.requireNonNullElse(
                        fieldSignature(parameter),
                        descriptor(parameter)
                    )
                )
                .collect(java.util.stream.Collectors.joining())
            + ")"
            + Objects.requireNonNullElse(
                fieldSignature(type.returnType()),
                descriptor(type.returnType())
            );
    }

    /** Supply the erased methods required by Java's generic ordering interfaces. */
    private void generateJavaBridges(
        final ClassBuilder writer,
        final String owner,
        final List<InterfaceType> interfaces
    ) {
        final Map<String, FunctionSymbol> bridges = new LinkedHashMap<>();
        collectJavaBridges(interfaces, bridges);
        for (final FunctionSymbol function : bridges.values()) {
            final FunctionType type = function.type();
            final String erased =
                "(" + "Ljava/lang/Object;".repeat(type.parameterTypes().size())
                    + ")I";
            if (erased.equals(methodDescriptor(type))) {
                continue;
            }
            generateMethod(
                writer,
                ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC,
                function.name(),
                erased,
                null,
                method -> {

                    method.aload(0);
                    final MethodGenerator generator =
                        new MethodGenerator(
                            method,
                            new IdentityHashMap<>(),
                            BuiltinType.I32,
                            null
                        );
                    for (int i = 0; i < type.parameterTypes().size(); i++) {
                        method.aload(i + 1);
                        generator.readObject(type.parameterTypes().get(i));
                    }
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc(owner),
                        function.name(),
                        MethodTypeDesc.ofDescriptor(methodDescriptor(type)),
                        false
                    );
                    method.ireturn();

                }
            );
        }
    }

    private void collectJavaBridges(
        final List<InterfaceType> interfaces,
        final Map<String, FunctionSymbol> methods
    ) {
        for (final InterfaceType type : interfaces) {
            final InterfaceContract contract = semanticModel.getInterface(type);
            if (
                type.javaClass() == Comparable.class
                    || type.javaClass() == Comparator.class
            ) {
                contract.methods().forEach(methods::putIfAbsent);
            }
            collectJavaBridges(contract.superInterfaces(), methods);
        }
    }

    private boolean referenceAssignable(
        final String source,
        final String target
    ) {
        if (source.equals(target) || target.equals("Ljava/lang/Object;")) {
            return true;
        }
        final Type sourceClass =
            generatedReferenceType(
                internalName(ClassDesc.ofDescriptor(source))
            );
        final Type targetClass =
            generatedReferenceType(
                internalName(ClassDesc.ofDescriptor(target))
            );
        return sourceClass != null && targetClass != null
            && semanticModel.isSubtype(sourceClass, targetClass);
    }

    private static @Nullable String boxedOwner(final Type type) {
        if (!(type instanceof BuiltinType builtin)) {
            return null;
        }
        return switch (builtin) {
            case I8 -> "java/lang/Byte";
            case I16 -> "java/lang/Short";
            case I32 -> "java/lang/Integer";
            case I64 -> "java/lang/Long";
            case F32 -> "java/lang/Float";
            case F64 -> "java/lang/Double";
            case BOOL -> "java/lang/Boolean";
            case CHAR -> "java/lang/Character";
            default -> null;
        };
    }

    private String printArgumentDescriptor(final Type type) {
        if (
            type instanceof ArrayType || type instanceof TupleType
                || type instanceof FunctionType
                || type instanceof BuiltinFunctionType
                || type instanceof ClassType
                || type instanceof InterfaceType
                || type instanceof UnionType
        ) {
            return "Ljava/lang/Object;";
        }
        return type == BuiltinType.I8 || type == BuiltinType.I16
            ? "I"
            : descriptor(type);
    }

    private String methodDescriptor(final FunctionType type) {
        final StringBuilder result = new StringBuilder("(");
        type.parameterTypes()
            .forEach(parameter -> result.append(descriptor(parameter)));
        return result.append(')')
            .append(descriptor(type.returnType()))
            .toString();
    }

    private static boolean reference(final Type type) {
        return type instanceof UnionType || type instanceof BuiltinFunctionType
            || type instanceof ClassType
            || type instanceof InterfaceType
            || type instanceof ArrayType
            || type instanceof TupleType
            || type instanceof FunctionType
            || type == BuiltinType.STRING
            || type == BuiltinType.NULL
            || type == BuiltinType.ANY;
    }

    private Opcode opcode(final Type type, final Opcode base) {
        final TypeKind kind = TypeKind.fromDescriptor(descriptor(type));
        if (kind == TypeKind.VOID) {
            return RETURN;
        }
        final boolean array = base == IALOAD || base == IASTORE;
        final String prefix = switch (array ? kind : kind.asLoadable()) {
            case BOOLEAN, BYTE -> array ? "B" : "I";
            case CHAR -> array ? "C" : "I";
            case SHORT -> array ? "S" : "I";
            case INT -> "I";
            case LONG -> "L";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case REFERENCE -> "A";
            case VOID -> throw new IllegalArgumentException("void instruction");
        };
        return Opcode.valueOf(prefix + base.name().substring(1));
    }

    private static java.lang.classfile.Instruction simpleInstruction(
        final Opcode opcode
    ) {
        return switch (opcode.kind()) {
            case RETURN -> ReturnInstruction.of(opcode);
            case ARRAY_LOAD -> ArrayLoadInstruction.of(opcode);
            case ARRAY_STORE -> ArrayStoreInstruction.of(opcode);
            case OPERATOR -> OperatorInstruction.of(opcode);
            case CONVERT -> ConvertInstruction.of(opcode);
            case STACK -> StackInstruction.of(opcode);
            case CONSTANT -> ConstantInstruction.ofIntrinsic(opcode);
            default -> throw new IllegalArgumentException(
                "Unsupported instruction: " + opcode
            );
        };
    }

    private static java.lang.classfile.Instruction localInstruction(
        final Opcode opcode,
        final int slot
    ) {
        return switch (opcode.kind()) {
            case LOAD -> LoadInstruction
                .of(LoadInstruction.of(opcode, slot).typeKind(), slot);
            case STORE -> StoreInstruction
                .of(StoreInstruction.of(opcode, slot).typeKind(), slot);
            default -> throw new IllegalArgumentException(
                "Unsupported local instruction: " + opcode
            );
        };
    }

    private static int slots(final Type type) {
        return type == BuiltinType.I64 || type == BuiltinType.F64 ? 2 : 1;
    }

    private Opcode loadOpcode(final Type type) {
        return opcode(type, ILOAD);
    }

    private Opcode storeOpcode(final Type type) {
        return opcode(type, ISTORE);
    }

    private Opcode returnOpcode(final Type type) {
        return opcode(type, IRETURN);
    }

    private Opcode arrayStoreOpcode(final Type type) {
        return opcode(type, IASTORE);
    }

    private static BytecodeException unsupported(
        final AstNode node,
        final String message
    ) {
        return new BytecodeException("%s at %s", message, node.range());
    }

    private static final class Loop {
        private final Label continueTarget;
        private final Label breakTarget;
        private boolean hasContinue;
        private boolean hasBreak;

        private Loop(final Label continueTarget, final Label breakTarget) {
            this.continueTarget = continueTarget;
            this.breakTarget = breakTarget;
        }
    }

    private final class MethodGenerator {
        private final CodeBuilder method;
        private final IdentityHashMap<Symbol, String> globals;
        private final @Nullable InstanceContext instance;
        private final IdentityHashMap<Symbol, Integer> locals =
            new IdentityHashMap<>();
        private final Deque<Loop> loops = new ArrayDeque<>();

        private record YieldTarget(Label label, boolean discarded) {
        }

        private final Deque<YieldTarget> yieldTargets = new ArrayDeque<>();
        private final Type returnType;
        private int nextLocal;
        private final IdentityHashMap<Expression, Integer> expressionTemporaries =
            new IdentityHashMap<>();
        private boolean beforeBaseInitialization;
        private String lambdaOwner;
        private final IdentityHashMap<Symbol, String> captureFields =
            new IdentityHashMap<>();
        private @Nullable String lexicalReceiverOwner;
        private @Nullable ClassType constructorSuperclass;
        private List<VariableDeclaration> constructorFields = List.of();

        private MethodGenerator(
            final CodeBuilder method,
            final IdentityHashMap<Symbol, String> globals,
            final Type returnType
        ) {
            this(method, globals, returnType, null);
        }

        private MethodGenerator(
            final CodeBuilder method,
            final IdentityHashMap<Symbol, String> globals,
            final Type returnType,
            final @Nullable InstanceContext instance
        ) {
            this.method = method;
            this.globals = globals;
            this.returnType = returnType;
            this.instance = instance;
            this.lambdaOwner = instance == null ? moduleName : instance.owner();
            this.nextLocal = instance == null ? 0 : 1;
        }

        private int local(final Symbol symbol) {
            return locals.computeIfAbsent(symbol, ignored -> {
                final int slot = nextLocal;
                nextLocal += cell(symbol) ? 1 : slots(symbol.type());
                return slot;
            });
        }

        private void finish(final boolean reachable) {
            if (reachable && returnType == BuiltinType.VOID) {
                method.return_();
            }
            else if (reachable) {
                // The semantic analyzer does not yet prove that every path returns.
                // Fail explicitly on fallthrough, while keeping the class verifiable.
                method.new_(classDesc("java/lang/IllegalStateException"));
                method.dup();
                method.ldc("Function completed without returning a value");
                method.invoke(
                    INVOKESPECIAL,
                    classDesc("java/lang/IllegalStateException"),
                    "<init>",
                    MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"),
                    false
                );
                method.athrow();
            }

        }

        private boolean block(final BlockStatement block) {
            for (final BlockItem child : block.items()) {
                if (!item(child)) {
                    return false;
                }
            }
            return true;
        }

        private void initializeBase(final List<Expression> arguments) {
            method.aload(0);
            final boolean previous = beforeBaseInitialization;
            beforeBaseInitialization = true;
            for (final Expression argument : arguments) {
                expression(argument);
            }
            beforeBaseInitialization = previous;
            final FunctionType type =
                constructorSuperclass == null
                    ? null
                    : semanticModel.getConstructor(constructorSuperclass);
            final boolean omitted =
                type != null && arguments.size() < type.parameterTypes().size();
            if (type != null && omitted) {
                final boolean[] assigned =
                    new boolean[type.parameterTypes().size()];
                for (int i = 0; i < assigned.length; i++) {
                    assigned[i] = i < arguments.size();
                    if (!assigned[i]) {
                        omittedValue(type.parameterTypes().get(i));
                    }
                }
                omissionMask(assigned);
            }
            method.invoke(
                INVOKESPECIAL,
                classDesc(
                    constructorSuperclass == null
                        ? "java/lang/Object"
                        : classOwner(constructorSuperclass)
                ),
                "<init>",
                MethodTypeDesc.ofDescriptor(
                    omitted
                        ? defaultDescriptor(Objects.requireNonNull(type))
                        : type == null ? "()V" : methodDescriptor(type)
                ),
                false
            );
            for (final VariableDeclaration field : constructorFields) {
                item(field);
            }
        }

        private boolean item(final BlockItem item) {
            switch (item) {
                case SuperConstructorCall call ->
                    initializeBase(call.arguments());
                case VariableDeclaration variable -> {
                    final Symbol symbol =
                        semanticModel.getSymbol(variable.name());
                    if (cell(symbol)) {
                        method.iconst_1();
                        if (reference(symbol.type())) {
                            method.anewarray(classDesc("java/lang/Object"));
                        }
                        else {
                            method.newarray(
                                RuntimeAbi.array(symbol.type()).creationKind
                            );
                        }
                        method.astore(local(symbol));
                    }
                    prepareStore(symbol);
                    final Expression initializer = variable.initializer();
                    expression(initializer);
                    store(symbol);
                }
                case DeclarationStatement declaration -> {
                    return item(declaration.declaration());
                }
                case BlockStatement block -> {
                    return block(block);
                }
                case ExpressionStatement statement -> {
                    if (
                        statement
                            .expression() instanceof IfExpression conditional
                    ) {
                        return conditional(conditional, method.newLabel());
                    }
                    discard(statement.expression());
                }
                case YieldStatement statement -> {
                    final YieldTarget target = yieldTargets.element();
                    if (target.discarded()) {
                        discard(statement.value());
                    }
                    else {
                        expression(statement.value());
                    }
                    method.branch(GOTO, target.label());
                    return false;
                }
                case ReturnStatement statement -> {
                    final Expression value = statement.value();
                    if (value != null) {
                        expression(value);
                    }
                    method.with(simpleInstruction(returnOpcode(returnType)));
                    return false;
                }
                case WhileStatement statement -> {
                    if (
                        constructorSuperclass != null
                            && isBooleanLiteral(statement.condition(), false)
                    ) {
                        return true;
                    }
                    final boolean alwaysTrue =
                        constructorSuperclass != null
                            && isBooleanLiteral(statement.condition(), true);
                    final Label condition = method.newLabel();
                    final Label end = method.newLabel();
                    method.labelBinding(condition);
                    if (!alwaysTrue) {
                        expression(statement.condition());
                        method.branch(IFEQ, end);
                    }
                    final Loop loop = new Loop(condition, end);
                    if (loopBody(statement.body(), loop)) {
                        method.branch(GOTO, condition);
                    }
                    method.labelBinding(end);
                    return !alwaysTrue || loop.hasBreak;
                }
                case DoWhileStatement statement -> {
                    final Label start = method.newLabel();
                    final Label condition = method.newLabel();
                    final Label end = method.newLabel();
                    method.labelBinding(start);
                    final Loop loop = new Loop(condition, end);
                    final boolean reachesCondition =
                        loopBody(statement.body(), loop) || loop.hasContinue;
                    if (reachesCondition) {
                        method.labelBinding(condition);
                        if (
                            constructorSuperclass != null
                                && isBooleanLiteral(statement.condition(), true)
                        ) {
                            method.branch(GOTO, start);
                        }
                        else if (
                            constructorSuperclass == null || !isBooleanLiteral(
                                statement.condition(),
                                false
                            )
                        ) {
                            expression(statement.condition());
                            method.branch(IFNE, start);
                        }
                    }
                    method.labelBinding(end);
                    return (reachesCondition && !(constructorSuperclass != null
                        && isBooleanLiteral(statement.condition(), true)))
                        || loop.hasBreak;
                }
                case ForStatement statement -> {
                    final Statement initializer = statement.initializer();
                    final Expression condition = statement.condition();
                    final Expression update = statement.update();
                    if (initializer != null) {
                        item(initializer);
                    }
                    if (
                        constructorSuperclass != null && condition != null
                            && isBooleanLiteral(condition, false)
                    ) {
                        return true;
                    }
                    final boolean alwaysTrue =
                        condition == null || (constructorSuperclass != null
                            && isBooleanLiteral(condition, true));
                    final Label start = method.newLabel();
                    final Label next = method.newLabel();
                    final Label end = method.newLabel();
                    method.labelBinding(start);
                    if (condition != null && !alwaysTrue) {
                        expression(condition);
                        method.branch(IFEQ, end);
                    }
                    final Loop loop = new Loop(next, end);
                    if (loopBody(statement.body(), loop) || loop.hasContinue) {
                        method.labelBinding(next);
                        if (update != null) {
                            discard(update);
                        }
                        method.branch(GOTO, start);
                    }
                    method.labelBinding(end);
                    return !alwaysTrue || loop.hasBreak;
                }
                case ForEachStatement statement -> forEach(statement);
                case BreakStatement statement -> {
                    if (loops.isEmpty()) {
                        throw unsupported(statement, "Break outside a loop");
                    }
                    loops.element().hasBreak = true;
                    method.branch(GOTO, loops.element().breakTarget);
                    return false;
                }
                case ContinueStatement statement -> {
                    if (loops.isEmpty()) {
                        throw unsupported(statement, "Continue outside a loop");
                    }
                    loops.element().hasContinue = true;
                    method.branch(GOTO, loops.element().continueTarget);
                    return false;
                }
                default -> throw unsupported(item, "Unsupported declaration");
            }
            return true;
        }

        private void selection(final SwitchExpression selection) {
            final boolean discarded =
                semanticModel.getExpressionType(selection) == BuiltinType.VOID;
            final Type subjectType =
                semanticModel.getEffectiveType(selection.subject());
            if (
                subjectType == BuiltinType.I32
                    && integerSelection(selection, discarded)
            ) {
                return;
            }
            final int subject = nextLocal;
            nextLocal += slots(subjectType);
            expression(selection.subject());
            method.with(localInstruction(storeOpcode(subjectType), subject));
            final Label end = method.newLabel();
            yieldTargets.push(new YieldTarget(end, discarded));
            for (final SwitchBranch branch : selection.branches()) {
                final Label body = method.newLabel();
                final Label next = method.newLabel();
                final List<Expression> matches = branch.matches();
                if (matches != null) {
                    for (final Expression match : matches) {
                        method.with(
                            localInstruction(loadOpcode(subjectType), subject)
                        );
                        expression(match);
                        if (
                            subjectType == BuiltinType.STRING
                                || subjectType instanceof UnionType
                                || subjectType == BuiltinType.ANY
                        ) {
                            method.invoke(
                                INVOKESTATIC,
                                classDesc("java/util/Objects"),
                                "equals",
                                MethodTypeDesc.ofDescriptor(
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                                ),
                                false
                            );
                            method.branch(IFNE, body);
                        }
                        else if (
                            subjectType == BuiltinType.F32
                                || subjectType == BuiltinType.F64
                                || subjectType == BuiltinType.I64
                        ) {
                            method.with(
                                simpleInstruction(
                                    subjectType == BuiltinType.I64
                                        ? LCMP
                                        : subjectType == BuiltinType.F64
                                            ? DCMPL
                                            : FCMPL
                                )
                            );
                            method.branch(IFEQ, body);
                        }
                        else {
                            method.branch(
                                reference(subjectType) ? IF_ACMPEQ : IF_ICMPEQ,
                                body
                            );
                        }
                    }
                }
                method.branch(GOTO, next);
                method.labelBinding(body);
                if (selectionBody(branch.body(), discarded)) {
                    method.branch(GOTO, end);
                }
                method.labelBinding(next);
            }
            final SwitchElseBranch otherwise = selection.elseBranch();
            if (otherwise != null) {
                selectionBody(otherwise.body(), discarded);
            }
            method.labelBinding(end);
            yieldTargets.pop();
        }

        private boolean integerSelection(
            final SwitchExpression selection,
            final boolean discarded
        ) {
            final TreeMap<Integer, Label> targets = new TreeMap<>();
            final List<Label> bodies = new ArrayList<>();
            for (final SwitchBranch branch : selection.branches()) {
                final Label body = method.newLabel();
                bodies.add(body);
                for (final Expression match : branch.matches()) {
                    final Object value = constantValue(match);
                    if (!(value instanceof Integer key)) {
                        return false;
                    }
                    // Duplicate matches still select the first branch in source order.
                    targets.putIfAbsent(key, body);
                }
            }
            if (targets.isEmpty()) {
                return false;
            }
            final Label end = method.newLabel();
            final Label otherwise = method.newLabel();
            expression(selection.subject());
            final int low = targets.firstKey();
            final int high = targets.lastKey();
            final long span = (long) high - low + 1;
            // Both instructions have the same padding. Choose the smaller encoding,
            // using long arithmetic so extreme integer keys cannot overflow the range.
            if (12L + 4L * span <= 8L + 8L * targets.size()) {
                final Label[] labels = new Label[(int) span];
                Arrays.fill(labels, otherwise);
                targets.forEach(
                    (key, label) -> labels[(int) ((long) key - low)] = label
                );
                method.tableswitch(
                    low,
                    high,
                    otherwise,
                    IntStream.range(0, labels.length)
                        .mapToObj(i -> SwitchCase.of(low + i, labels[i]))
                        .toList()
                );
            }
            else {

                method.lookupswitch(
                    otherwise,
                    targets.entrySet()
                        .stream()
                        .map(
                            entry -> SwitchCase
                                .of(entry.getKey(), entry.getValue())
                        )
                        .toList()
                );
            }
            yieldTargets.push(new YieldTarget(end, discarded));
            for (int index = 0; index < selection.branches().size(); index++) {
                method.labelBinding(bodies.get(index));
                if (
                    selectionBody(
                        selection.branches().get(index).body(),
                        discarded
                    )
                ) {
                    method.branch(GOTO, end);
                }
            }
            method.labelBinding(otherwise);
            final SwitchElseBranch elseBranch = selection.elseBranch();
            if (elseBranch != null) {
                selectionBody(elseBranch.body(), discarded);
            }
            method.labelBinding(end);
            yieldTargets.pop();
            return true;
        }

        private boolean selectionBody(
            final SwitchBranchBody body,
            final boolean discarded
        ) {
            return switch (body) {
                case SwitchBranchExpressionBody compact -> {
                    if (discarded) {
                        discard(compact.expression());
                    }
                    else {
                        expression(compact.expression());
                    }
                    yield true;
                }
                case SwitchBranchBlockBody block -> item(block.block());
            };
        }

        private boolean conditional(
            final IfExpression conditional,
            final Label end
        ) {
            boolean reachable =
                branch(conditional.condition(), conditional.thenBranch(), end);
            for (final ElseIfBranch branch : conditional.elifBranches()) {
                reachable |= branch(branch.condition(), branch.branch(), end);
            }
            final BlockStatement otherwise = conditional.elseBranch();
            if (otherwise != null) {
                reachable |= block(otherwise);
            }
            method.labelBinding(end);
            return otherwise == null || reachable;
        }

        private boolean branch(
            final Expression condition,
            final BlockStatement body,
            final Label end
        ) {
            final Label next = method.newLabel();
            expression(condition);
            method.branch(IFEQ, next);
            final boolean reachable = block(body);
            if (reachable) {
                method.branch(GOTO, end);
            }
            method.labelBinding(next);
            return reachable;
        }

        private boolean loopBody(final BlockStatement body, final Loop loop) {
            loops.push(loop);
            final boolean reachable = block(body);
            loops.pop();
            return reachable;
        }

        private void forEach(final ForEachStatement statement) {
            final int array = nextLocal++;
            final int index = nextLocal++;
            final Symbol value = semanticModel.getSymbol(statement.value());
            expression(statement.iterable());
            method.astore(array);
            method.iconst_0();
            method.istore(index);
            final Label start = method.newLabel();
            final Label next = method.newLabel();
            final Label end = method.newLabel();
            method.labelBinding(start);
            method.iload(index);
            method.aload(array);
            arrayCall(value.type(), RuntimeAbi.ArrayMethod.SIZE);
            method.branch(IF_ICMPGE, end);
            method.aload(array);
            method.iload(index);
            arrayGet(value.type());
            store(value);
            final IdentifierDeclaration indexName = statement.index();
            if (indexName != null) {
                // Copy into the source-level const binding; only the hidden
                // loop counter is incremented by compiler-generated code.
                method.iload(index);
                store(semanticModel.getSymbol(indexName));
            }
            final Loop loop = new Loop(next, end);
            if (loopBody(statement.body(), loop) || loop.hasContinue) {
                method.labelBinding(next);
                method.iinc(index, 1);
                method.branch(GOTO, start);
            }
            method.labelBinding(end);
        }

        private void load(final Symbol symbol) {
            if (BuiltinFunctionSymbol.PRINT.equals(symbol)) {
                method.fieldAccess(
                    GETSTATIC,
                    classDesc("java/lang/System"),
                    "out",
                    ClassDesc.ofDescriptor("Ljava/io/PrintStream;")
                );
            }
            else if (symbol instanceof FunctionSymbol function) {
                final boolean instanceMethod =
                    instance != null && instance.members().containsKey(symbol);
                method
                    .ldc(
                        MethodHandleDesc
                            .ofMethod(
                                instanceMethod
                                    ? DirectMethodHandleDesc.Kind.VIRTUAL
                                    : DirectMethodHandleDesc.Kind.STATIC,
                                classDesc(
                                    instanceMethod
                                        ? Objects.requireNonNull(instance)
                                            .owner()
                                        : moduleName
                                ),
                                symbol.name(),
                                MethodTypeDesc.ofDescriptor(
                                    methodDescriptor(function.type())
                                )
                            )
                    );
                if (instanceMethod) {
                    method.aload(0);
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc("java/lang/invoke/MethodHandle"),
                        "bindTo",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"
                        ),
                        false
                    );
                }
            }
            else if (
                instance != null && instance.members().containsKey(symbol)
            ) {
                method.aload(0);
                method.fieldAccess(
                    GETFIELD,
                    classDesc(instance.owner()),
                    Objects.requireNonNull(instance.members().get(symbol)),
                    ClassDesc.ofDescriptor(descriptor(symbol.type()))
                );
            }
            else if (captureFields.containsKey(symbol)) {
                loadCaptureStorage(symbol);
                if (cell(symbol)) {
                    method.iconst_0();
                    method
                        .with(simpleInstruction(opcode(symbol.type(), IALOAD)));
                    if (reference(symbol.type())) {
                        readObject(symbol.type());
                    }
                }
            }
            else if (globals.containsKey(symbol)) {
                method.fieldAccess(
                    GETSTATIC,
                    classDesc(moduleName),
                    Objects.requireNonNull(globals.get(symbol)),
                    ClassDesc.ofDescriptor(descriptor(symbol.type()))
                );
            }
            else {
                final Integer slot = locals.get(symbol);
                if (slot == null) {
                    throw new IllegalArgumentException(
                        "No local storage for " + symbol.name()
                    );
                }
                if (cell(symbol)) {
                    method.aload(slot);
                    method.iconst_0();
                    method
                        .with(simpleInstruction(opcode(symbol.type(), IALOAD)));
                    if (reference(symbol.type())) {
                        readObject(symbol.type());
                    }
                }
                else {
                    method.with(
                        localInstruction(loadOpcode(symbol.type()), slot)
                    );
                }
            }
        }

        private boolean prepareStore(final Symbol symbol) {
            if (instance != null && instance.members().containsKey(symbol)) {
                method.aload(0);
                return true;
            }
            return false;
        }

        // Instance stores expect the receiver below the value on the stack.
        private void store(final Symbol symbol) {
            if (cell(symbol)) {
                final int value = nextLocal;
                nextLocal += slots(symbol.type());
                method
                    .with(localInstruction(storeOpcode(symbol.type()), value));
                loadCaptureStorage(symbol);
                method.iconst_0();
                method.with(localInstruction(loadOpcode(symbol.type()), value));
                method.with(simpleInstruction(arrayStoreOpcode(symbol.type())));
                return;
            }
            if (instance != null && instance.members().containsKey(symbol)) {
                method.fieldAccess(
                    PUTFIELD,
                    classDesc(instance.owner()),
                    Objects.requireNonNull(instance.members().get(symbol)),
                    ClassDesc.ofDescriptor(descriptor(symbol.type()))
                );
            }
            else if (globals.containsKey(symbol)) {
                method.fieldAccess(
                    PUTSTATIC,
                    classDesc(moduleName),
                    Objects.requireNonNull(globals.get(symbol)),
                    ClassDesc.ofDescriptor(descriptor(symbol.type()))
                );
            }
            else {
                method.with(
                    localInstruction(storeOpcode(symbol.type()), local(symbol))
                );
            }
        }

        private void discard(final Expression expression) {
            expression(expression);
            if (
                semanticModel.getEffectiveType(expression) != BuiltinType.VOID
            ) {
                method.with(
                    simpleInstruction(
                        slots(semanticModel.getEffectiveType(expression)) == 2
                            ? POP2
                            : POP
                    )
                );
            }
        }

        private void expression(final Expression expression) {
            final Integer temporary = expressionTemporaries.get(expression);
            if (temporary != null) {
                method.with(
                    localInstruction(
                        loadOpcode(semanticModel.getEffectiveType(expression)),
                        temporary
                    )
                );
                return;
            }

            if (
                expression instanceof UnaryExpression unary
                    && SemanticAnalyzer.integerLiteral(unary) != null
            ) {
                if (
                    semanticModel
                        .getEffectiveType(expression) instanceof UnionType
                        || semanticModel.getEffectiveType(
                            expression
                        ) instanceof InterfaceType
                        || semanticModel
                            .getEffectiveType(expression) == BuiltinType.ANY
                ) {
                    final var value =
                        Objects.requireNonNull(
                            SemanticAnalyzer.integerLiteral(unary)
                        );
                    if (
                        semanticModel
                            .getExpressionType(expression) == BuiltinType.I64
                    ) {
                        method.ldc(value.longValueExact());
                    }
                    else {
                        method.ldc(value.intValueExact());
                    }
                    convertExpression(expression);
                    return;
                }
                method.ldc(
                    (ConstantDesc) Objects.requireNonNull(constantValue(unary))
                );
                return;
            }
            switch (expression) {
                case FormatStringExpression format -> {
                    method.new_(classDesc("java/lang/StringBuilder"));
                    method.dup();
                    method.invoke(
                        INVOKESPECIAL,
                        classDesc("java/lang/StringBuilder"),
                        "<init>",
                        MethodTypeDesc.ofDescriptor("()V"),
                        false
                    );
                    for (final Expression part : format.parts()) {
                        expression(part);
                        final String argument =
                            printArgumentDescriptor(
                                semanticModel.getEffectiveType(part)
                            );
                        method.invoke(
                            INVOKEVIRTUAL,
                            classDesc("java/lang/StringBuilder"),
                            "append",
                            MethodTypeDesc.ofDescriptor(
                                "(" + argument + ")Ljava/lang/StringBuilder;"
                            ),
                            false
                        );
                    }
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc("java/lang/StringBuilder"),
                        "toString",
                        MethodTypeDesc.ofDescriptor("()Ljava/lang/String;"),
                        false
                    );
                }
                case LiteralExpression literal -> literal(literal);
                case IdentifierExpression identifier ->
                    load(semanticModel.getReference(identifier));
                case GroupingExpression grouping ->
                    expression(grouping.expression());
                case BinaryExpression binary -> binary(binary);
                case UnaryExpression unary -> {
                    switch (unary.operator()) {
                        case INCREMENT,
                            DECREMENT -> increment(
                                unary.operand(),
                                unary.operator() == UnaryOperator.INCREMENT,
                                false
                            );
                        case PLUS -> expression(unary.operand());
                        case MINUS -> {
                            expression(unary.operand());
                            method
                                .with(
                                    simpleInstruction(
                                        opcode(
                                            semanticModel
                                                .getExpressionType(unary),
                                            INEG
                                        )
                                    )
                                );
                            narrow(semanticModel.getExpressionType(unary));
                        }
                        case NOT -> {
                            expression(unary.operand());
                            booleanResult(IFEQ);
                        }
                    }
                }
                case PostfixExpression postfix -> increment(
                    postfix.operand(),
                    postfix.operator() == PostfixOperator.INCREMENT,
                    true
                );
                case TernaryExpression ternary -> {
                    final Label otherwise = method.newLabel();
                    final Label end = method.newLabel();
                    expression(ternary.condition());
                    method.branch(IFEQ, otherwise);
                    expression(ternary.thenBranch());
                    method.branch(GOTO, end);
                    method.labelBinding(otherwise);
                    expression(ternary.elseBranch());
                    method.labelBinding(end);
                }
                case IfExpression conditional -> {
                    final Label end = method.newLabel();
                    yieldTargets.push(new YieldTarget(end, false));
                    conditional(conditional, end);
                    yieldTargets.pop();
                }
                case SwitchExpression selection -> selection(selection);
                case AssignmentExpression assignment -> assign(assignment);
                case CallExpression call -> call(call);
                case MemberExpression member -> member(member);
                case ThisExpression self -> {
                    method.aload(0);
                    if (lexicalReceiverOwner != null) {
                        method.fieldAccess(
                            GETFIELD,
                            classDesc(Objects.requireNonNull(instance).owner()),
                            "$receiver",
                            ClassDesc
                                .ofDescriptor("L" + lexicalReceiverOwner + ";")
                        );
                    }
                }
                case NewExpression creation -> {
                    if (
                        semanticModel.getExpressionType(
                            creation
                        ) instanceof InterfaceType javaType
                    ) {
                        final var constructor =
                            semanticModel.getJavaConstructor(creation);
                        final String owner = interfaceOwner(javaType);
                        method.new_(classDesc(owner));
                        method.dup();
                        for (int i = 0; i < creation.arguments().size(); i++) {
                            final Expression argument =
                                creation.arguments().get(i);
                            expression(argument);
                            if (
                                !constructor.getParameterTypes()[i]
                                    .isPrimitive()
                            ) {
                                box(semanticModel.getEffectiveType(argument));
                            }
                        }
                        method.invoke(
                            INVOKESPECIAL,
                            classDesc(owner),
                            "<init>",
                            MethodTypeDesc.ofDescriptor(
                                MethodTypeDesc
                                    .of(
                                        ClassDesc.ofDescriptor("V"),
                                        Arrays
                                            .stream(
                                                constructor.getParameterTypes()
                                            )
                                            .map(
                                                type -> type.describeConstable()
                                                    .orElseThrow()
                                            )
                                            .toArray(ClassDesc[]::new)
                                    )
                                    .descriptorString()
                            ),
                            false
                        );
                        break;
                    }
                    final String name =
                        ((ClassType) semanticModel.getExpressionType(creation))
                            .name();
                    final String owner =
                        classOwners.getOrDefault(name, moduleName + "$" + name);
                    method.new_(classDesc(owner));
                    method.dup();
                    for (final Expression argument : creation.arguments()) {
                        expression(argument);
                    }
                    final FunctionType constructorType =
                        semanticModel.getConstructor(
                            (ClassType) semanticModel
                                .getExpressionType(creation)
                        );
                    final boolean omitted =
                        creation.arguments()
                            .size() < constructorType.parameterTypes().size();
                    if (omitted) {
                        final boolean[] assigned =
                            new boolean[constructorType.parameterTypes()
                                .size()];
                        for (int i = 0; i < assigned.length; i++) {
                            assigned[i] = i < creation.arguments().size();
                            if (!assigned[i]) {
                                omittedValue(
                                    constructorType.parameterTypes().get(i)
                                );
                            }
                        }
                        omissionMask(assigned);
                    }
                    method.invoke(
                        INVOKESPECIAL,
                        classDesc(owner),
                        "<init>",
                        MethodTypeDesc.ofDescriptor(
                            omitted
                                ? defaultDescriptor(constructorType)
                                : methodDescriptor(constructorType)
                        ),
                        false
                    );
                }
                case NamedArgumentExpression named ->
                    throw unsupported(named, "Named argument outside a call");
                case TupleExpression tuple -> tuple(tuple);
                case ArraySpread spread -> throw unsupported(
                    spread,
                    "Array spread must be compiled in an array literal"
                );
                case ArrayExpression array -> array(array);
                case SubscriptExpression index -> {
                    if (
                        semanticModel.getExpressionType(
                            index.target()
                        ) instanceof TupleType
                    ) {
                        tupleGet(index);
                    }
                    else {
                        arrayIndex(index);
                        arrayGet(semanticModel.getExpressionType(index));
                    }
                }
                case SliceExpression slice -> slice(slice);
                case ObjectExpression object -> object(object);
                case LambdaExpression lambda -> lambda(lambda);
            }
            convertExpression(expression);
        }

        private void convertExpression(final Expression expression) {
            final Type from = semanticModel.getExpressionType(expression);
            final Type to = semanticModel.getEffectiveType(expression);
            if (
                to == BuiltinType.ANY || (to instanceof InterfaceType
                    && JavaTypes.boxedClass(from) != null)
            ) {
                box(from);
            }
            else if (to instanceof UnionType) {
                final Type member =
                    semanticModel.getUnionMemberType(expression);
                if (!(from instanceof UnionType)) {
                    if (
                        member instanceof InterfaceType
                            && JavaTypes.boxedClass(from) != null
                    ) {
                        box(from);
                    }
                    else {
                        convert(from, member);
                        box(member);
                    }
                }
                final String target = descriptor(to);
                if (
                    !referenceAssignable(boxedDescriptor(member), target)
                        && !(unwrap(
                            expression
                        ) instanceof LiteralExpression literal
                            && literal.kind() == LiteralKind.NULL)
                ) {
                    // A null-typed variable has an Object descriptor even though its only value is null.
                    method.checkcast(
                        classDesc(internalName(ClassDesc.ofDescriptor(target)))
                    );
                }
            }
            else {
                convert(from, to);
            }
        }

        private boolean cell(final Symbol symbol) {
            return semanticModel.isCapturedMutable(symbol)
                && !globals.containsKey(symbol)
                && (instance == null
                    || !instance.members().containsKey(symbol));
        }

        private void readObject(final Type type) {
            final String owner = boxedOwner(type);
            if (owner != null) {
                method.checkcast(classDesc(owner));
                final String valueMethod = switch ((BuiltinType) type) {
                    case I8 -> "byteValue";
                    case I16 -> "shortValue";
                    case I32 -> "intValue";
                    case I64 -> "longValue";
                    case F32 -> "floatValue";
                    case F64 -> "doubleValue";
                    case BOOL -> "booleanValue";
                    case CHAR -> "charValue";
                    default ->
                        throw new IllegalArgumentException("Not a primitive");
                };
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(owner),
                    valueMethod,
                    MethodTypeDesc.ofDescriptor("()" + descriptor(type)),
                    false
                );
            }
            else if (!descriptor(type).equals("Ljava/lang/Object;")) {
                method.checkcast(
                    classDesc(
                        internalName(ClassDesc.ofDescriptor(descriptor(type)))
                    )
                );
            }
        }

        private String captureDescriptor(final Symbol symbol) {
            return cell(symbol)
                ? reference(symbol.type())
                    ? "[Ljava/lang/Object;"
                    : "[" + descriptor(symbol.type())
                : descriptor(symbol.type());
        }

        private void loadCaptureStorage(final Symbol symbol) {
            if (captureFields.containsKey(symbol)) {
                method.aload(0);
                method.fieldAccess(
                    GETFIELD,
                    classDesc(Objects.requireNonNull(instance).owner()),
                    Objects.requireNonNull(captureFields.get(symbol)),
                    ClassDesc.ofDescriptor(captureDescriptor(symbol))
                );
            }
            else if (cell(symbol)) {
                method.aload(local(symbol));
            }
            else {
                load(symbol);
            }
        }

        private void object(final ObjectExpression object) {
            final InterfaceType type =
                (InterfaceType) semanticModel.getExpressionType(object);
            final InterfaceContract contract = semanticModel.getInterface(type);
            ObjectInfo info = objects.get(object);
            if (info == null) {
                final List<Symbol> captures = new ArrayList<>();
                boolean receiver = false;
                for (final ObjectMember member : semanticModel
                    .getObjectMembers(object)) {
                    if (
                        contract.methods().containsKey(member.name().name())
                            && !semanticModel.isSpreadMethod(member)
                    ) {
                        final LambdaExpression lambda =
                            (LambdaExpression) unwrap(member.value());
                        for (final Symbol capture : semanticModel
                            .getLambdaCaptures(lambda)) {
                            if (
                                (locals.containsKey(capture)
                                    || captureFields.containsKey(capture))
                                    && !captures.contains(capture)
                            ) {
                                captures.add(capture);
                            }
                        }
                        receiver |=
                            AstTraversal.anyMatch(
                                lambda.body(),
                                ThisExpression.class::isInstance
                            );
                    }
                }
                final String owner =
                    Objects.requireNonNull(objectNames.get(object));
                final String receiverOwner =
                    receiver
                        ? lexicalReceiverOwner != null
                            ? lexicalReceiverOwner
                            : Objects.requireNonNull(instance).owner()
                        : null;
                final StringBuilder signature = new StringBuilder("(");
                for (final ObjectMember member : semanticModel
                    .getObjectMembers(object)) {
                    final VariableSymbol field =
                        contract.fields().get(member.name().name());
                    if (field != null) {
                        signature.append(descriptor(field.type()));
                    }
                    else if (semanticModel.isSpreadMethod(member)) {
                        signature
                            .append(
                                descriptor(
                                    semanticModel.getExpressionType(
                                        ((MemberExpression) member.value())
                                            .target()
                                    )
                                )
                            );
                    }
                }
                for (final Symbol capture : captures) {
                    signature.append(captureDescriptor(capture));
                }
                if (receiver) {
                    signature.append("L").append(receiverOwner).append(";");
                }
                signature.append(")V");
                info =
                    new ObjectInfo(
                        owner,
                        List.copyOf(captures),
                        receiver,
                        signature.toString()
                    );
                objects.put(object, info);
                objectTypes.put(owner, type);

                final ClassBuilder saved = currentWriter;
                try {
                    objectClasses.put(
                        owner,
                        generateObject(object, contract, info, receiverOwner)
                    );
                }
                finally {
                    currentWriter = saved;
                }
            }
            final IdentityHashMap<Expression, Integer> values =
                new IdentityHashMap<>();
            final boolean spreadObject =
                object.members()
                    .stream()
                    .anyMatch(ObjectSpread.class::isInstance);
            if (
                spreadObject || semanticModel.getObjectEvaluation(object)
                    .size() != semanticModel.getObjectMembers(object).size()
            ) {
                for (final ObjectEntry entry : semanticModel
                    .getObjectEvaluation(object)) {
                    if (entry instanceof ObjectSpread spread) {
                        expression(spread.value());
                        final int slot = nextLocal++;
                        method.astore(slot);
                        values.put(spread.value(), slot);
                    }
                    else {
                        final ObjectMember member = (ObjectMember) entry;
                        if (
                            (semanticModel.isSpreadMethod(member)
                                && !contract.fields()
                                    .containsKey(member.name().name()))
                                || (contract.methods()
                                    .containsKey(member.name().name())
                                    && unwrap(
                                        member.value()
                                    ) instanceof LambdaExpression)
                        ) {
                            continue;
                        }
                        if (
                            member.value() instanceof MemberExpression access
                                && values.containsKey(access.target())
                        ) {
                            expressionTemporaries.put(
                                access.target(),
                                Objects
                                    .requireNonNull(values.get(access.target()))
                            );
                            try {
                                expression(member.value());
                            }
                            finally {
                                expressionTemporaries.remove(access.target());
                            }
                        }
                        else {
                            expression(member.value());
                        }
                        final Type valueType =
                            semanticModel.getEffectiveType(member.value());
                        final int slot = nextLocal;
                        nextLocal += slots(valueType);
                        method.with(
                            localInstruction(storeOpcode(valueType), slot)
                        );
                        values.put(member.value(), slot);
                    }
                }
            }
            method.new_(classDesc(info.owner()));
            method.dup();
            for (final ObjectMember member : semanticModel
                .getObjectMembers(object)) {
                if (contract.fields().containsKey(member.name().name())) {
                    if (values.containsKey(member.value())) {
                        method.with(
                            localInstruction(
                                loadOpcode(
                                    semanticModel
                                        .getEffectiveType(member.value())
                                ),
                                Objects
                                    .requireNonNull(values.get(member.value()))
                            )
                        );
                    }
                    else {
                        expression(member.value());
                    }
                }
                else if (semanticModel.isSpreadMethod(member)) {
                    method
                        .aload(
                            Objects
                                .requireNonNull(
                                    values.get(
                                        ((MemberExpression) member.value())
                                            .target()
                                    )
                                )
                        );
                }
            }
            for (final Symbol capture : info.captures()) {
                loadCaptureStorage(capture);
            }
            if (info.receiver()) {
                method.aload(0);
                if (lexicalReceiverOwner != null) {
                    method.fieldAccess(
                        GETFIELD,
                        classDesc(Objects.requireNonNull(instance).owner()),
                        "$receiver",
                        ClassDesc.ofDescriptor("L" + lexicalReceiverOwner + ";")
                    );
                }
            }
            method.invoke(
                INVOKESPECIAL,
                classDesc(info.owner()),
                "<init>",
                MethodTypeDesc.ofDescriptor(info.constructorDescriptor()),
                false
            );
        }

        private byte[] generateObject(
            final ObjectExpression object,
            final InterfaceContract contract,
            final ObjectInfo info,
            final @Nullable String receiverOwner
        ) {
            return classFile().build(classDesc(info.owner()), writer -> {
                final List<InnerClassInfo> innerClasses = new ArrayList<>();
                final List<ClassDesc> nestMembers = new ArrayList<>();
                writer.withVersion(JAVA_21_VERSION, 0);
                writer.withFlags(ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC);
                writer.withSuperclass(classDesc("java/lang/Object"));
                generateClassSignature(
                    writer,
                    classSignature(
                        "java/lang/Object",
                        List.of(
                            (InterfaceType) semanticModel
                                .getExpressionType(object)
                        )
                    )
                );
                writer.withInterfaceSymbols(
                    Arrays
                        .stream(
                            new String[] {interfaceOwner(
                                (InterfaceType) semanticModel
                                    .getExpressionType(object)
                            )}
                        )
                        .map(BytecodeGenerator::classDesc)
                        .toList()
                );

                currentWriter = writer;

                writer.with(NestHostAttribute.of(classDesc(moduleName)));
                final Map<String, String> constructorFields =
                    new LinkedHashMap<>();
                for (final ObjectMember member : semanticModel
                    .getObjectMembers(object)) {
                    final VariableSymbol field =
                        contract.fields().get(member.name().name());
                    if (field != null) {
                        generateField(
                            writer,
                            ACC_PRIVATE
                                | (field.mutability() == Mutability.CONST
                                    ? ACC_FINAL
                                    : 0),
                            field.name(),
                            descriptor(field.type()),
                            null,
                            null
                        );
                        constructorFields
                            .put(field.name(), descriptor(field.type()));
                        generateGetter(
                            writer,
                            field.name(),
                            info.owner(),
                            field.type()
                        );
                        if (field.mutability() == Mutability.VAR) {
                            generateSetter(
                                writer,
                                field.name(),
                                info.owner(),
                                field.type()
                            );
                        }
                    }
                    else if (semanticModel.isSpreadMethod(member)) {
                        final String name = "$delegate$" + member.name().name();
                        final String sourceDescriptor =
                            descriptor(
                                semanticModel.getExpressionType(
                                    ((MemberExpression) member.value()).target()
                                )
                            );
                        generateField(
                            writer,
                            ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC,
                            name,
                            sourceDescriptor,
                            null,
                            null
                        );
                        constructorFields.put(name, sourceDescriptor);
                    }
                }
                final IdentityHashMap<Symbol, String> fields =
                    new IdentityHashMap<>();
                for (int i = 0; i < info.captures().size(); i++) {
                    final Symbol capture = info.captures().get(i);
                    final String name = "$capture" + i;
                    fields.put(capture, name);
                    generateField(
                        writer,
                        ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC,
                        name,
                        captureDescriptor(capture),
                        null,
                        null
                    );
                    constructorFields.put(name, captureDescriptor(capture));
                }
                if (receiverOwner != null) {
                    generateField(
                        writer,
                        ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC,
                        "$receiver",
                        "L" + receiverOwner + ";",
                        null,
                        null
                    );
                    constructorFields
                        .put("$receiver", "L" + receiverOwner + ";");
                }
                generateMethod(
                    writer,
                    ACC_PUBLIC,
                    "<init>",
                    info.constructorDescriptor(),
                    null,
                    constructor -> {

                        constructor.aload(0);
                        constructor.invoke(
                            INVOKESPECIAL,
                            classDesc("java/lang/Object"),
                            "<init>",
                            MethodTypeDesc.ofDescriptor("()V"),
                            false
                        );
                        int slot = 1;
                        for (final var field : constructorFields.entrySet()) {
                            final TypeKind fieldType =
                                TypeKind.fromDescriptor(field.getValue());
                            constructor.aload(0);
                            constructor.with(
                                localInstruction(
                                    LoadInstruction.of(fieldType, slot)
                                        .opcode(),
                                    slot
                                )
                            );
                            constructor.fieldAccess(
                                PUTFIELD,
                                classDesc(info.owner()),
                                field.getKey(),
                                ClassDesc.ofDescriptor(field.getValue())
                            );
                            slot += fieldType.slotSize();
                        }
                        constructor.return_();

                    }
                );
                for (final ObjectMember member : semanticModel
                    .getObjectMembers(object)) {
                    final FunctionSymbol implementation =
                        contract.methods().get(member.name().name());
                    if (implementation == null) {
                        continue;
                    }
                    if (semanticModel.isSpreadMethod(member)) {
                        final MemberExpression access =
                            (MemberExpression) member.value();
                        final Type source =
                            semanticModel.getExpressionType(access.target());
                        final FunctionType signature = implementation.type();
                        generateMethod(
                            writer,
                            ACC_PUBLIC,
                            member.name().name(),
                            methodDescriptor(signature),
                            null,
                            body -> {

                                body.aload(0);
                                body.fieldAccess(
                                    GETFIELD,
                                    classDesc(info.owner()),
                                    "$delegate$" + member.name().name(),
                                    ClassDesc.ofDescriptor(descriptor(source))
                                );
                                int parameterSlot = 1;
                                for (final Type parameter : signature
                                    .parameterTypes()) {
                                    body.with(
                                        localInstruction(
                                            loadOpcode(parameter),
                                            parameterSlot
                                        )
                                    );
                                    parameterSlot += slots(parameter);
                                }
                                body.invoke(
                                    source instanceof InterfaceType
                                        ? INVOKEINTERFACE
                                        : INVOKEVIRTUAL,
                                    classDesc(typeOwner(source)),
                                    member.name().name(),
                                    MethodTypeDesc.ofDescriptor(
                                        methodDescriptor(signature)
                                    ),
                                    source instanceof InterfaceType
                                );
                                body.with(
                                    simpleInstruction(
                                        returnOpcode(signature.returnType())
                                    )
                                );

                            }
                        );
                        // Named interfaces already supply their own default overloads.
                        // Inferred contracts retain the source method's defaults instead.
                        if (
                            ((InterfaceType) semanticModel
                                .getExpressionType(object)).name()
                                .startsWith("$spread")
                                && hasDefaultParameters(implementation)
                        ) {
                            generateMethod(
                                writer,
                                ACC_PUBLIC | ACC_SYNTHETIC,
                                member.name().name(),
                                defaultDescriptor(signature),
                                null,
                                defaults -> {

                                    defaults.aload(0);
                                    defaults.fieldAccess(
                                        GETFIELD,
                                        classDesc(info.owner()),
                                        "$delegate$" + member.name().name(),
                                        ClassDesc
                                            .ofDescriptor(descriptor(source))
                                    );
                                    int defaultSlot = 1;
                                    for (final Type parameter : signature
                                        .parameterTypes()) {
                                        defaults.with(
                                            localInstruction(
                                                loadOpcode(parameter),
                                                defaultSlot
                                            )
                                        );
                                        defaultSlot += slots(parameter);
                                    }
                                    defaults.aload(defaultSlot);
                                    defaults.invoke(
                                        source instanceof InterfaceType
                                            ? INVOKEINTERFACE
                                            : INVOKEVIRTUAL,
                                        classDesc(typeOwner(source)),
                                        member.name().name(),
                                        MethodTypeDesc.ofDescriptor(
                                            defaultDescriptor(signature)
                                        ),
                                        source instanceof InterfaceType
                                    );
                                    defaults.with(
                                        simpleInstruction(
                                            returnOpcode(signature.returnType())
                                        )
                                    );

                                }
                            );
                        }
                        continue;
                    }
                    final LambdaExpression lambda =
                        (LambdaExpression) unwrap(member.value());
                    final FunctionType signature = implementation.type();
                    generateMethod(
                        writer,
                        ACC_PUBLIC,
                        member.name().name(),
                        methodDescriptor(signature),
                        null,
                        body -> {
                            final MethodGenerator generator =
                                new MethodGenerator(
                                    body,
                                    globals,
                                    signature.returnType(),
                                    new InstanceContext(
                                        info.owner(),
                                        new IdentityHashMap<>()
                                    )
                                );
                            generator.captureFields.putAll(fields);
                            generator.lexicalReceiverOwner = receiverOwner;

                            for (final LambdaParameter parameter : lambda
                                .parameters()) {
                                generator.local(
                                    semanticModel.getSymbol(parameter.name())
                                );
                            }
                            if (
                                lambda.body() instanceof Expression expression
                            ) {
                                generator.expression(expression);
                                body.with(
                                    simpleInstruction(
                                        returnOpcode(signature.returnType())
                                    )
                                );
                                generator.finish(false);
                            }
                            else {
                                generator.finish(
                                    generator
                                        .block((BlockStatement) lambda.body())
                                );
                            }
                        }
                    );
                }
                generateJavaBridges(
                    writer,
                    info.owner(),
                    List.of(
                        (InterfaceType) semanticModel.getExpressionType(object)
                    )
                );

                if (!innerClasses.isEmpty()) {
                    writer.with(InnerClassesAttribute.of(innerClasses));
                }
                if (!nestMembers.isEmpty()) {
                    writer.with(NestMembersAttribute.ofSymbols(nestMembers));
                }
            });
        }

        private void lambda(final LambdaExpression lambda) {
            final Type lambdaType = semanticModel.getExpressionType(lambda);
            final InterfaceType comparator =
                lambdaType instanceof InterfaceType contract ? contract : null;
            final FunctionType type =
                comparator != null
                    ? JavaTypes.comparatorFunction(comparator)
                    : (FunctionType) lambdaType;
            if (comparator != null) {
                method.ldc(
                    java.util.Comparator.class.describeConstable().orElseThrow()
                );
            }
            final List<Symbol> captures =
                semanticModel.getLambdaCaptures(lambda)
                    .stream()
                    .filter(locals::containsKey)
                    .toList();
            final String name = "$lambda" + nextLambda++;
            final StringBuilder signature = new StringBuilder("(");
            for (final Symbol capture : captures) {
                signature.append(
                    cell(capture)
                        ? reference(capture.type())
                            ? "[Ljava/lang/Object;"
                            : "[" + descriptor(capture.type())
                        : descriptor(capture.type())
                );
            }
            for (final Type parameter : type.parameterTypes()) {
                signature.append(descriptor(parameter));
            }
            signature.append(")").append(descriptor(type.returnType()));
            final String owner = lambdaOwner;
            final InstanceContext lambdaInstance =
                beforeBaseInitialization
                    || semanticModel.isReceiverlessLambda(lambda)
                        ? null
                        : instance;
            generateMethod(
                Objects.requireNonNull(currentWriter),
                ACC_PRIVATE | ACC_SYNTHETIC
                    | (lambdaInstance == null ? ACC_STATIC : 0),
                name,
                signature.toString(),
                null,
                body -> {
                    final MethodGenerator generator =
                        new MethodGenerator(
                            body,
                            globals,
                            type.returnType(),
                            lambdaInstance
                        );
                    generator.lambdaOwner = owner;
                    generator.captureFields.putAll(captureFields);
                    generator.lexicalReceiverOwner = lexicalReceiverOwner;

                    for (final Symbol capture : captures) {
                        generator.local(capture);
                    }
                    for (final LambdaParameter parameter : lambda
                        .parameters()) {
                        generator
                            .local(semanticModel.getSymbol(parameter.name()));
                    }
                    if (lambda.body() instanceof Expression expression) {
                        generator.expression(expression);
                        body.with(
                            simpleInstruction(returnOpcode(type.returnType()))
                        );
                        generator.finish(false);
                    }
                    else {
                        generator.finish(
                            generator.block((BlockStatement) lambda.body())
                        );
                    }
                }
            );
            method.ldc(
                MethodHandleDesc.ofMethod(
                    lambdaInstance == null
                        ? DirectMethodHandleDesc.Kind.STATIC
                        : DirectMethodHandleDesc.Kind.VIRTUAL,
                    classDesc(owner),
                    name,
                    MethodTypeDesc.ofDescriptor(signature.toString())
                )
            );
            if (lambdaInstance != null) {
                method.aload(0);
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc("java/lang/invoke/MethodHandle"),
                    "bindTo",
                    MethodTypeDesc.ofDescriptor(
                        "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"
                    ),
                    false
                );
            }
            if (!captures.isEmpty()) {
                method.iconst_0();
                method.ldc(captures.size());
                method.anewarray(classDesc("java/lang/Object"));
                for (int i = 0; i < captures.size(); i++) {
                    final Symbol capture = captures.get(i);
                    method.dup();
                    method.ldc(i);
                    if (cell(capture)) {
                        method.aload(local(capture));
                    }
                    else {
                        load(capture);
                        box(capture.type());
                    }
                    method.aastore();
                }
                method.invoke(
                    INVOKESTATIC,
                    classDesc("java/lang/invoke/MethodHandles"),
                    "insertArguments",
                    MethodTypeDesc.ofDescriptor(
                        "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"
                    ),
                    false
                );
            }
            if (comparator != null) {
                method.invoke(
                    INVOKESTATIC,
                    classDesc("java/lang/invoke/MethodHandleProxies"),
                    "asInterfaceInstance",
                    MethodTypeDesc.ofDescriptor(
                        "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;"
                    ),
                    false
                );
                method.checkcast(classDesc("java/util/Comparator"));
            }
        }

        private void box(final Type type) {
            final String owner = boxedOwner(type);
            if (owner != null) {
                method.invoke(
                    INVOKESTATIC,
                    classDesc(owner),
                    "valueOf",
                    MethodTypeDesc.ofDescriptor(
                        "(" + descriptor(type) + ")L" + owner + ";"
                    ),
                    false
                );
            }
        }

        private void narrow(final Type type) {
            if (type == BuiltinType.I8) {
                method.i2b();
            }
            if (type == BuiltinType.I16) {
                method.i2s();
            }
        }

        private void convert(final Type from, final Type to) {
            if (from.equals(to)) {
                return;
            }
            if (from == BuiltinType.I64) {
                if (to == BuiltinType.F32) {
                    method.l2f();
                }
                else if (to == BuiltinType.F64) {
                    method.l2d();
                }
                else {
                    method.l2i();
                }
            }
            else if (from == BuiltinType.F64 && to == BuiltinType.F32) {
                method.d2f();
            }
            else if (from == BuiltinType.F32 && to == BuiltinType.F64) {
                method.f2d();
            }
            else if (
                from instanceof BuiltinType builtin
                    && (builtin.isInteger() || builtin == BuiltinType.CHAR)
            ) {
                if (to == BuiltinType.I64) {
                    method.i2l();
                }
                if (to == BuiltinType.F32) {
                    method.i2f();
                }
                if (to == BuiltinType.F64) {
                    method.i2d();
                }
            }
            narrow(to);
        }

        private void literal(final LiteralExpression literal) {
            final String text = literal.text();
            switch (literal.kind()) {
                case INT -> method.ldc((ConstantDesc) integerConstant(literal));
                case FLOAT ->
                    method.ldc((ConstantDesc) floatingConstant(literal));
                case BOOL -> method.with(
                    simpleInstruction(
                        Boolean.parseBoolean(text) ? ICONST_1 : ICONST_0
                    )
                );
                case NULL -> method.aconst_null();
                case CHAR -> method.ldc((int) decodeChar(text));
                case STRING -> method.ldc(decodeString(text));
                case RAW_STRING ->
                    method.ldc(text.substring(1, text.length() - 1));
            }
        }

        private void call(final CallExpression call) {
            final Type calleeType =
                semanticModel.getExpressionType(call.callee());
            if (calleeType == BuiltinFunctionType.ARRAY_SORT) {
                final MemberExpression member =
                    (MemberExpression) unwrap(call.callee());
                expression(member.target());
                final ArrayType array =
                    (ArrayType) semanticModel
                        .getExpressionType(member.target());
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(RuntimeAbi.array(array.elementType()).owner),
                    "sort",
                    MethodTypeDesc.ofDescriptor("()V"),
                    false
                );
                return;
            }
            if (calleeType == BuiltinFunctionType.PRINT) {
                expression(call.callee());
                for (final Expression argument : call.arguments()) {
                    expression(argument);
                }
                final String argumentDescriptor =
                    call.arguments().isEmpty()
                        ? ""
                        : printArgumentDescriptor(
                            semanticModel
                                .getEffectiveType(call.arguments().getFirst())
                        );
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc("java/io/PrintStream"),
                    "println",
                    MethodTypeDesc
                        .ofDescriptor("(" + argumentDescriptor + ")V"),
                    false
                );
                return;
            }
            final FunctionType type = (FunctionType) calleeType;
            final Expression callee = unwrap(call.callee());
            if (
                callee instanceof MemberExpression member
                    && semanticModel.getReference(
                        member.member()
                    ) instanceof JavaMethodSymbol function
            ) {
                final var javaMethod = function.method();
                final boolean isStatic =
                    java.lang.reflect.Modifier
                        .isStatic(javaMethod.getModifiers());
                if (!isStatic) {
                    expression(member.target());
                    box(semanticModel.getEffectiveType(member.target()));
                }
                for (int i = 0; i < call.arguments().size(); i++) {
                    final Expression argument = call.arguments().get(i);
                    expression(argument);
                    if (!javaMethod.getParameterTypes()[i].isPrimitive()) {
                        box(semanticModel.getEffectiveType(argument));
                    }
                }
                if (
                    function.name().equals("sort") && call.arguments().isEmpty()
                        && javaMethod.getParameterCount() == 1
                ) {
                    method.aconst_null();
                }
                final boolean isInterface =
                    javaMethod.getDeclaringClass().isInterface();
                method.invoke(
                    isStatic
                        ? INVOKESTATIC
                        : isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL,
                    classDesc(
                        javaMethod.getDeclaringClass()
                            .getName()
                            .replace('.', '/')
                    ),
                    function.name(),
                    MethodTypeDesc.ofDescriptor(
                        javaMethodDescriptor(javaMethod).descriptorString()
                    ),
                    isInterface
                );
                if (!javaMethod.getReturnType().isPrimitive()) {
                    readObject(type.returnType());
                }
                return;
            }
            if (
                callee instanceof MemberExpression member
                    && semanticModel.getReference(
                        member.member()
                    ) instanceof FunctionSymbol function
            ) {
                memberReceiver(member);
                callArguments(call);
                method.invoke(
                    semanticModel
                        .getMemberOwner(member) instanceof InterfaceType
                            ? INVOKEINTERFACE
                            : INVOKEVIRTUAL,
                    classDesc(memberOwner(member)),
                    function.name(),
                    MethodTypeDesc
                        .ofDescriptor(callDescriptor(call, function.type())),
                    semanticModel
                        .getMemberOwner(member) instanceof InterfaceType
                );
            }
            else if (
                callee instanceof IdentifierExpression identifier
                    && semanticModel.getReference(
                        identifier
                    ) instanceof FunctionSymbol function
            ) {
                final boolean instanceMethod =
                    instance != null
                        && instance.members().containsKey(function);
                if (instanceMethod) {
                    method.aload(0);
                }
                callArguments(call);
                method.invoke(
                    instanceMethod ? INVOKEVIRTUAL : INVOKESTATIC,
                    classDesc(
                        instanceMethod
                            ? Objects.requireNonNull(instance).owner()
                            : moduleName
                    ),
                    function.name(),
                    MethodTypeDesc.ofDescriptor(callDescriptor(call, type)),
                    false
                );
            }
            else {
                expression(call.callee());
                callArguments(call);
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc("java/lang/invoke/MethodHandle"),
                    "invokeExact",
                    MethodTypeDesc.ofDescriptor(methodDescriptor(type)),
                    false
                );
            }
        }

        private String callDescriptor(
            final CallExpression call,
            final FunctionType type
        ) {
            return call.arguments().size() < type.parameterTypes().size()
                ? defaultDescriptor(type)
                : methodDescriptor(type);
        }

        private void omittedValue(final Type type) {
            switch (descriptor(type)) {
                case "J" -> method.lconst_0();
                case "F" -> method.fconst_0();
                case "D" -> method.dconst_0();
                case "I", "B", "S", "C", "Z" -> method.iconst_0();
                default -> method.aconst_null();
            }
        }

        private void omissionMask(final boolean[] assigned) {
            method.ldc(assigned.length);
            method.newarray(TypeKind.BOOLEAN);
            for (int i = 0; i < assigned.length; i++) {
                if (!assigned[i]) {
                    method.dup();
                    method.ldc(i);
                    method.iconst_1();
                    method.bastore();
                }
            }
        }

        private void callArguments(final CallExpression call) {
            final FunctionType type =
                (FunctionType) semanticModel.getExpressionType(call.callee());
            if (
                call.arguments().size() == type.parameterTypes().size() && call
                    .arguments()
                    .stream()
                    .noneMatch(
                        argument -> argument instanceof NamedArgumentExpression
                    )
            ) {
                for (final Expression argument : call.arguments()) {
                    expression(argument);
                }
                return;
            }
            final List<Integer> parameters =
                semanticModel.getArgumentParameters(call);
            final int[] locals = new int[type.parameterTypes().size()];
            final boolean[] assigned = new boolean[locals.length];
            for (int i = 0; i < parameters.size(); i++) {
                final Expression supplied = call.arguments().get(i);
                final Expression value =
                    supplied instanceof NamedArgumentExpression named
                        ? named.value()
                        : supplied;
                final int parameter = parameters.get(i);
                final Type parameterType = type.parameterTypes().get(parameter);
                final int slot = nextLocal;
                nextLocal += slots(parameterType);
                locals[parameter] = slot;
                assigned[parameter] = true;
                expression(value);
                method.with(localInstruction(storeOpcode(parameterType), slot));
            }
            for (int i = 0; i < locals.length; i++) {
                if (assigned[i]) {
                    method.with(
                        localInstruction(
                            loadOpcode(type.parameterTypes().get(i)),
                            locals[i]
                        )
                    );
                }
                else {
                    omittedValue(type.parameterTypes().get(i));
                }
            }
            if (parameters.size() < locals.length) {
                omissionMask(assigned);
            }
        }

        private void tuple(final TupleExpression tuple) {
            final String owner =
                "com/github/andreasarvidsson/eld/runtime/EldTuple";
            method.new_(classDesc(owner));
            method.dup();
            method.ldc(tuple.elements().size());
            method.anewarray(classDesc("java/lang/Object"));
            for (int i = 0; i < tuple.elements().size(); i++) {
                final Expression element = tuple.elements().get(i);
                method.dup();
                method.ldc(i);
                expression(element);
                box(semanticModel.getEffectiveType(element));
                method.aastore();
            }
            method.invoke(
                INVOKESPECIAL,
                classDesc(owner),
                "<init>",
                MethodTypeDesc.ofDescriptor("([Ljava/lang/Object;)V"),
                false
            );
        }

        private void tupleGet(final SubscriptExpression index) {
            expression(index.target());
            method.ldc(
                Objects
                    .requireNonNull(
                        SemanticAnalyzer.integerLiteral(index.index())
                    )
                    .intValue()
            );
            method.invoke(
                INVOKEVIRTUAL,
                classDesc("com/github/andreasarvidsson/eld/runtime/EldTuple"),
                "get",
                MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Object;"),
                false
            );
            final Type element = semanticModel.getExpressionType(index);
            final String owner = boxedOwner(element);
            if (owner != null) {
                method.checkcast(classDesc(owner));
                final String valueMethod = switch ((BuiltinType) element) {
                    case I8 -> "byteValue";
                    case I16 -> "shortValue";
                    case I32 -> "intValue";
                    case I64 -> "longValue";
                    case F32 -> "floatValue";
                    case F64 -> "doubleValue";
                    case BOOL -> "booleanValue";
                    case CHAR -> "charValue";
                    default -> throw new IllegalArgumentException(
                        "Not a primitive tuple element"
                    );
                };
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(owner),
                    valueMethod,
                    MethodTypeDesc.ofDescriptor("()" + descriptor(element)),
                    false
                );
            }
            else if (!descriptor(element).equals("Ljava/lang/Object;")) {
                method.checkcast(
                    classDesc(
                        internalName(
                            ClassDesc.ofDescriptor(descriptor(element))
                        )
                    )
                );
            }
        }

        private void spreadArray(final ArrayExpression array) {
            final Type element =
                ((ArrayType) semanticModel.getExpressionType(array))
                    .elementType();
            final RuntimeAbi.ArrayKind runtime = RuntimeAbi.array(element);
            final List<Integer> values = new ArrayList<>();
            final List<Integer> lengths = new ArrayList<>();
            final int total = nextLocal++;
            method.iconst_0();
            method.istore(total);
            for (int entryIndex = 0; entryIndex < array.elements()
                .size(); entryIndex++) {
                final Expression entry = array.elements().get(entryIndex);
                final int value = nextLocal;
                if (entry instanceof ArraySpread spread) {
                    final Type sourceElement =
                        ((ArrayType) semanticModel
                            .getExpressionType(spread.expression()))
                            .elementType();
                    expression(spread.expression());
                    // Snapshot only when later expressions might mutate the contributed range.
                    if (
                        array.elements()
                            .subList(entryIndex + 1, array.elements().size())
                            .stream()
                            .anyMatch(
                                later -> AstTraversal.anyMatch(
                                    later,
                                    node -> node instanceof CallExpression
                                        || node instanceof NewExpression
                                        || node instanceof AssignmentExpression
                                        || node instanceof UnaryExpression
                                        || node instanceof PostfixExpression
                                )
                            )
                    ) {
                        arrayCall(sourceElement, RuntimeAbi.ArrayMethod.COPY);
                    }
                    nextLocal++;
                    method.astore(value);
                    final int length = nextLocal++;
                    method.aload(value);
                    arrayCall(sourceElement, RuntimeAbi.ArrayMethod.SIZE);
                    method.istore(length);
                    lengths.add(length);
                    method.iload(total);
                    method.iload(length);
                }
                else {
                    expression(entry);
                    nextLocal += slots(element);
                    method.with(localInstruction(storeOpcode(element), value));
                    lengths.add(-1);
                    method.iload(total);
                    method.iconst_1();
                }
                values.add(value);
                method.iadd();
                method.istore(total);
            }
            final int result = nextLocal++;
            method.iload(total);
            if (runtime == RuntimeAbi.ArrayKind.OBJECT) {
                method.anewarray(classDesc("java/lang/Object"));
            }
            else {
                method.newarray(runtime.creationKind);
            }
            method.astore(result);
            final int offset = nextLocal++;
            method.iconst_0();
            method.istore(offset);
            for (int i = 0; i < array.elements().size(); i++) {
                final Expression entry = array.elements().get(i);
                if (entry instanceof ArraySpread spread) {
                    final Type sourceElement =
                        ((ArrayType) semanticModel
                            .getExpressionType(spread.expression()))
                            .elementType();
                    final RuntimeAbi.ArrayKind sourceRuntime =
                        RuntimeAbi.array(sourceElement);
                    if (
                        sourceRuntime == runtime
                            && semanticModel.getEffectiveType(spread)
                                .equals(sourceElement)
                    ) {
                        method.aload(values.get(i));
                        method.aload(result);
                        method.iload(offset);
                        method.iload(lengths.get(i));
                        method.invoke(
                            INVOKEVIRTUAL,
                            classDesc(sourceRuntime.owner),
                            "copyTo",
                            MethodTypeDesc.ofDescriptor(
                                "(" + runtime.backingDescriptor + "II)V"
                            ),
                            false
                        );
                    }
                    else {
                        // Widening and boxing require element conversion; identical storage uses arraycopy.
                        final int index = nextLocal++;
                        method.iconst_0();
                        method.istore(index);
                        final Label start = method.newLabel();
                        final Label end = method.newLabel();
                        method.labelBinding(start);
                        method.iload(index);
                        method.iload(lengths.get(i));
                        method.branch(IF_ICMPGE, end);
                        method.aload(result);
                        method.iload(offset);
                        method.iload(index);
                        method.iadd();
                        method.aload(values.get(i));
                        method.iload(index);
                        arrayGet(sourceElement);
                        convertExpression(spread);
                        method
                            .with(simpleInstruction(arrayStoreOpcode(element)));
                        method.iinc(index, 1);
                        method.branch(GOTO, start);
                        method.labelBinding(end);
                    }
                    method.iload(offset);
                    method.iload(lengths.get(i));
                    method.iadd();
                    method.istore(offset);
                }
                else {
                    method.aload(result);
                    method.iload(offset);
                    method.with(
                        localInstruction(loadOpcode(element), values.get(i))
                    );
                    method.with(simpleInstruction(arrayStoreOpcode(element)));
                    method.iinc(offset, 1);
                }
            }
            method.new_(classDesc(runtime.owner));
            method.dup();
            method.aload(result);
            method.invoke(
                INVOKESPECIAL,
                classDesc(runtime.owner),
                "<init>",
                MethodTypeDesc.ofDescriptor(runtime.constructorDescriptor),
                false
            );
        }

        private void array(final ArrayExpression array) {
            if (
                array.elements()
                    .stream()
                    .anyMatch(ArraySpread.class::isInstance)
            ) {
                spreadArray(array);
                return;
            }
            if (
                semanticModel.getExpressionType(array) instanceof InterfaceType
            ) {
                method.new_(classDesc("java/util/ArrayList"));
                method.dup();
                method.ldc(array.elements().size());
                method.invoke(
                    INVOKESPECIAL,
                    classDesc("java/util/ArrayList"),
                    "<init>",
                    MethodTypeDesc.ofDescriptor("(I)V"),
                    false
                );
                for (final Expression element : array.elements()) {
                    method.dup();
                    expression(element);
                    box(semanticModel.getEffectiveType(element));
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc("java/util/ArrayList"),
                        "add",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Z"),
                        false
                    );
                    method.pop();
                }
                return;
            }
            final Type element =
                ((ArrayType) semanticModel.getExpressionType(array))
                    .elementType();
            final RuntimeAbi.ArrayKind runtime = RuntimeAbi.array(element);
            method.new_(classDesc(runtime.owner));
            method.dup();
            if (array.elements().isEmpty()) {
                method.invoke(
                    INVOKESPECIAL,
                    classDesc(runtime.owner),
                    "<init>",
                    MethodTypeDesc
                        .ofDescriptor(RuntimeAbi.EMPTY_ARRAY_CONSTRUCTOR),
                    false
                );
                return;
            }
            method.ldc(array.elements().size());
            if (runtime == RuntimeAbi.ArrayKind.OBJECT) {
                method.anewarray(classDesc("java/lang/Object"));
            }
            else {
                method.newarray(runtime.creationKind);
            }
            for (int i = 0; i < array.elements().size(); i++) {
                method.dup();
                method.ldc(i);
                expression(array.elements().get(i));
                method.with(simpleInstruction(arrayStoreOpcode(element)));
            }
            method.invoke(
                INVOKESPECIAL,
                classDesc(runtime.owner),
                "<init>",
                MethodTypeDesc.ofDescriptor(runtime.constructorDescriptor),
                false
            );
        }

        private void slice(final SliceExpression slice) {
            expression(slice.target());
            final Expression start = slice.startIndex();
            final Expression end = slice.endIndex();
            if (start != null) {
                expression(start);
            }
            if (end != null) {
                expression(end);
            }
            arrayCall(
                ((ArrayType) semanticModel.getExpressionType(slice))
                    .elementType(),
                start == null
                    ? (end == null
                        ? RuntimeAbi.ArrayMethod.COPY
                        : RuntimeAbi.ArrayMethod.SLICE_TO)
                    : (end == null
                        ? RuntimeAbi.ArrayMethod.SLICE_FROM
                        : RuntimeAbi.ArrayMethod.SLICE)
            );
        }

        private void arrayIndex(final SubscriptExpression index) {
            expression(index.target());
            expression(index.index());
        }

        private void arrayCall(
            final Type element,
            final RuntimeAbi.ArrayMethod operation
        ) {
            final RuntimeAbi.ArrayKind array = RuntimeAbi.array(element);
            method.invoke(
                INVOKEVIRTUAL,
                classDesc(array.owner),
                operation.methodName,
                MethodTypeDesc.ofDescriptor(array.methodDescriptor(operation)),
                false
            );
        }

        private void arrayGet(final Type element) {
            arrayCall(element, RuntimeAbi.ArrayMethod.GET);
            if (
                RuntimeAbi.array(element) == RuntimeAbi.ArrayKind.OBJECT
                    && !descriptor(element).equals("Ljava/lang/Object;")
            ) {
                method.checkcast(
                    classDesc(
                        internalName(
                            ClassDesc.ofDescriptor(descriptor(element))
                        )
                    )
                );
            }
        }

        private void arraySet(final Type element) {
            arrayCall(element, RuntimeAbi.ArrayMethod.SET);
        }

        private Expression unwrap(final Expression expression) {
            return expression instanceof GroupingExpression grouping
                ? unwrap(grouping.expression())
                : expression;
        }

        private boolean isBooleanLiteral(
            final Expression expression,
            final boolean value
        ) {
            return unwrap(expression) instanceof LiteralExpression literal
                && literal.kind() == LiteralKind.BOOL
                && Boolean.parseBoolean(literal.text()) == value;
        }

        private String memberOwner(final MemberExpression member) {
            return typeOwner(semanticModel.getMemberOwner(member));
        }

        private void memberReceiver(final MemberExpression member) {
            expression(member.target());
        }

        private void member(final MemberExpression member) {
            final Symbol symbol = semanticModel.getReference(member.member());
            if (symbol instanceof JavaMethodSymbol function) {
                final var javaMethod = function.method();
                final boolean isInterface =
                    javaMethod.getDeclaringClass().isInterface();
                method.ldc(
                    MethodHandleDesc.ofMethod(
                        java.lang.reflect.Modifier.isStatic(
                            javaMethod.getModifiers()
                        )
                            ? DirectMethodHandleDesc.Kind.STATIC
                            : isInterface
                                ? DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL
                                : DirectMethodHandleDesc.Kind.VIRTUAL,
                        classDesc(
                            javaMethod.getDeclaringClass()
                                .getName()
                                .replace('.', '/')
                        ),
                        function.name(),
                        MethodTypeDesc.ofDescriptor(
                            javaMethodDescriptor(javaMethod).descriptorString()
                        )
                    )
                );
                if (
                    !java.lang.reflect.Modifier
                        .isStatic(javaMethod.getModifiers())
                ) {
                    expression(member.target());
                    box(semanticModel.getEffectiveType(member.target()));
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc("java/lang/invoke/MethodHandle"),
                        "bindTo",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"
                        ),
                        false
                    );
                }
                method.ldc(
                    MethodTypeDesc
                        .ofDescriptor(methodDescriptor(function.type()))
                );
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc("java/lang/invoke/MethodHandle"),
                    "asType",
                    MethodTypeDesc.ofDescriptor(
                        "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"
                    ),
                    false
                );
                return;
            }
            if (symbol instanceof FunctionSymbol function) {
                method.ldc(
                    MethodHandleDesc.ofMethod(
                        semanticModel
                            .getMemberOwner(member) instanceof InterfaceType
                                ? DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL
                                : DirectMethodHandleDesc.Kind.VIRTUAL,
                        classDesc(memberOwner(member)),
                        symbol.name(),
                        MethodTypeDesc
                            .ofDescriptor(methodDescriptor(function.type()))
                    )
                );
                memberReceiver(member);
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc("java/lang/invoke/MethodHandle"),
                    "bindTo",
                    MethodTypeDesc.ofDescriptor(
                        "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;"
                    ),
                    false
                );
            }
            else if (
                semanticModel.getMemberOwner(member) instanceof InterfaceType
            ) {
                memberReceiver(member);
                method.invoke(
                    INVOKEINTERFACE,
                    classDesc(memberOwner(member)),
                    "$get$" + symbol.name(),
                    MethodTypeDesc
                        .ofDescriptor("()" + descriptor(symbol.type())),
                    true
                );
            }
            else {
                memberReceiver(member);
                method.fieldAccess(
                    GETFIELD,
                    classDesc(memberOwner(member)),
                    symbol.name(),
                    ClassDesc.ofDescriptor(descriptor(symbol.type()))
                );
            }
        }

        private void assign(final AssignmentExpression assignment) {
            final Expression target = unwrap(assignment.target());
            if (target instanceof IdentifierExpression identifier) {
                final Symbol symbol = semanticModel.getReference(identifier);
                final boolean instanceField = prepareStore(symbol);
                expression(assignment.value());
                method.with(
                    simpleInstruction(
                        slots(symbol.type()) == 2
                            ? (instanceField ? DUP2_X1 : DUP2)
                            : (instanceField ? DUP_X1 : DUP)
                    )
                );
                store(symbol);
            }
            else if (target instanceof MemberExpression member) {
                final Type type = semanticModel.getExpressionType(member);
                memberReceiver(member);
                expression(assignment.value());
                method.with(
                    simpleInstruction(slots(type) == 2 ? DUP2_X1 : DUP_X1)
                );
                storeMember(member, type);
            }
            else if (target instanceof SubscriptExpression index) {
                arrayIndex(index);
                expression(assignment.value());
                method.with(
                    simpleInstruction(
                        slots(semanticModel.getExpressionType(index)) == 2
                            ? DUP2_X2
                            : DUP_X2
                    )
                );
                arraySet(semanticModel.getExpressionType(index));
            }
            else {
                throw unsupported(target, "Invalid assignment target");
            }
        }

        private void storeMember(
            final MemberExpression member,
            final Type type
        ) {
            final String name =
                semanticModel.getReference(member.member()).name();
            if (semanticModel.getMemberOwner(member) instanceof InterfaceType) {
                method.invoke(
                    INVOKEINTERFACE,
                    classDesc(memberOwner(member)),
                    "$set$" + name,
                    MethodTypeDesc.ofDescriptor("(" + descriptor(type) + ")V"),
                    true
                );
            }
            else {
                method.fieldAccess(
                    PUTFIELD,
                    classDesc(memberOwner(member)),
                    name,
                    ClassDesc.ofDescriptor(descriptor(type))
                );
            }
        }

        private void increment(
            final Expression operand,
            final boolean increase,
            final boolean postfix
        ) {
            final Expression target = unwrap(operand);
            final Type type = semanticModel.getExpressionType(target);
            if (target instanceof IdentifierExpression identifier) {
                final Symbol symbol = semanticModel.getReference(identifier);
                final boolean instanceField = prepareStore(symbol);
                load(symbol);
                if (postfix) {
                    method.with(
                        simpleInstruction(
                            slots(symbol.type()) == 2
                                ? (instanceField ? DUP2_X1 : DUP2)
                                : (instanceField ? DUP_X1 : DUP)
                        )
                    );
                }
                addOne(type, increase);
                if (!postfix) {
                    method.with(
                        simpleInstruction(
                            slots(symbol.type()) == 2
                                ? (instanceField ? DUP2_X1 : DUP2)
                                : (instanceField ? DUP_X1 : DUP)
                        )
                    );
                }
                store(symbol);
            }
            else if (target instanceof MemberExpression member) {
                memberReceiver(member);
                method.dup();
                if (
                    semanticModel
                        .getMemberOwner(member) instanceof InterfaceType
                ) {
                    method.invoke(
                        INVOKEINTERFACE,
                        classDesc(memberOwner(member)),
                        "$get$" + semanticModel.getReference(member.member())
                            .name(),
                        MethodTypeDesc.ofDescriptor("()" + descriptor(type)),
                        true
                    );
                }
                else {
                    method.fieldAccess(
                        GETFIELD,
                        classDesc(memberOwner(member)),
                        semanticModel.getReference(member.member()).name(),
                        ClassDesc.ofDescriptor(descriptor(type))
                    );
                }
                if (postfix) {
                    method.with(
                        simpleInstruction(slots(type) == 2 ? DUP2_X1 : DUP_X1)
                    );
                }
                addOne(type, increase);
                if (!postfix) {
                    method.with(
                        simpleInstruction(slots(type) == 2 ? DUP2_X1 : DUP_X1)
                    );
                }
                storeMember(member, type);
            }
            else if (target instanceof SubscriptExpression index) {
                arrayIndex(index);
                method.dup2();
                arrayGet(type);
                if (postfix) {
                    method.with(
                        simpleInstruction(
                            slots(semanticModel.getExpressionType(index)) == 2
                                ? DUP2_X2
                                : DUP_X2
                        )
                    );
                }
                addOne(type, increase);
                if (!postfix) {
                    method.with(
                        simpleInstruction(
                            slots(semanticModel.getExpressionType(index)) == 2
                                ? DUP2_X2
                                : DUP_X2
                        )
                    );
                }
                arraySet(type);
            }
            else {
                throw unsupported(operand, "Invalid increment target");
            }
        }

        private void addOne(final Type type, final boolean increase) {
            method.with(
                simpleInstruction(
                    type == BuiltinType.F64
                        ? DCONST_1
                        : type == BuiltinType.I64
                            ? LCONST_1
                            : type == BuiltinType.F32 ? FCONST_1 : ICONST_1
                )
            );
            method
                .with(simpleInstruction(opcode(type, increase ? IADD : ISUB)));
            narrow(type);
            if (type == BuiltinType.CHAR) {
                method.i2c();
            }
        }

        private void binary(final BinaryExpression binary) {
            final BinaryOperator operator = binary.operator();
            if (
                operator == BinaryOperator.AND || operator == BinaryOperator.OR
            ) {
                final Label shortcut = method.newLabel();
                final Label end = method.newLabel();
                expression(binary.left());
                method.branch(
                    operator == BinaryOperator.AND ? IFEQ : IFNE,
                    shortcut
                );
                expression(binary.right());
                method.branch(GOTO, end);
                method.labelBinding(shortcut);
                method.with(
                    simpleInstruction(
                        operator == BinaryOperator.AND ? ICONST_0 : ICONST_1
                    )
                );
                method.labelBinding(end);
                return;
            }
            final Type type = semanticModel.getEffectiveType(binary.left());
            expression(binary.left());
            expression(binary.right());
            if (operator == BinaryOperator.ADD && type == BuiltinType.STRING) {
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc("java/lang/String"),
                    "concat",
                    MethodTypeDesc
                        .ofDescriptor("(Ljava/lang/String;)Ljava/lang/String;"),
                    false
                );
                return;
            }
            if (operator.isBool()) {
                if (reference(type)) {
                    if (
                        operator != BinaryOperator.EQUAL
                            && operator != BinaryOperator.NOT_EQUAL
                    ) {
                        throw unsupported(
                            binary,
                            "Unsupported reference comparison"
                        );
                    }
                    if (
                        type == BuiltinType.STRING || type instanceof UnionType
                            || type instanceof TupleType
                            || type == BuiltinType.ANY
                    ) {
                        method.invoke(
                            INVOKESTATIC,
                            classDesc("java/util/Objects"),
                            "equals",
                            MethodTypeDesc.ofDescriptor(
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z"
                            ),
                            false
                        );
                        if (operator == BinaryOperator.NOT_EQUAL) {
                            method.iconst_1();
                            method.ixor();
                        }
                    }
                    else {
                        booleanResult(
                            operator == BinaryOperator.EQUAL
                                ? IF_ACMPEQ
                                : IF_ACMPNE
                        );
                    }
                }
                else {
                    final Opcode opcode = switch (operator) {
                        case EQUAL -> IF_ICMPEQ;
                        case NOT_EQUAL -> IF_ICMPNE;
                        case LESS -> IF_ICMPLT;
                        case LESS_EQUAL -> IF_ICMPLE;
                        case GREATER -> IF_ICMPGT;
                        case GREATER_EQUAL -> IF_ICMPGE;
                        default ->
                            throw unsupported(binary, "Unsupported comparison");
                    };
                    if (type == BuiltinType.I64) {
                        method.lcmp();
                        booleanResult(
                            Opcode
                                .valueOf(opcode.name().replace("IF_ICMP", "IF"))
                        );
                    }
                    else if (
                        type == BuiltinType.F32 || type == BuiltinType.F64
                    ) {
                        // Choose the NaN result so ordered comparisons remain false.
                        method.with(
                            simpleInstruction(
                                operator == BinaryOperator.LESS
                                    || operator == BinaryOperator.LESS_EQUAL
                                        ? (type == BuiltinType.F64
                                            ? DCMPG
                                            : FCMPG)
                                        : (type == BuiltinType.F64
                                            ? DCMPL
                                            : FCMPL)
                            )
                        );
                        booleanResult(
                            Opcode
                                .valueOf(opcode.name().replace("IF_ICMP", "IF"))
                        );
                    }
                    else {
                        booleanResult(opcode);
                    }
                }
                return;
            }
            method.with(simpleInstruction(opcode(type, switch (operator) {
                case ADD -> IADD;
                case SUBTRACT -> ISUB;
                case MULTIPLY -> IMUL;
                case DIVIDE -> IDIV;
                case MODULO -> IREM;
                default -> throw unsupported(
                    binary,
                    "Unsupported arithmetic operator"
                );
            })));
            narrow(type);
        }

        private void booleanResult(final Opcode opcode) {
            final Label yes = method.newLabel();
            final Label end = method.newLabel();
            method.branch(opcode, yes);
            method.iconst_0();
            method.branch(GOTO, end);
            method.labelBinding(yes);
            method.iconst_1();
            method.labelBinding(end);
        }
    }
}
