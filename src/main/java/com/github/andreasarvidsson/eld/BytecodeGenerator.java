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
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.*;
import com.github.andreasarvidsson.eld.runtime.RuntimeAbi;
import com.github.andreasarvidsson.eld.semantic.ArrayType;
import com.github.andreasarvidsson.eld.semantic.TupleType;
import com.github.andreasarvidsson.eld.semantic.BuiltinFunctionSymbol;
import com.github.andreasarvidsson.eld.semantic.BuiltinFunctionType;
import com.github.andreasarvidsson.eld.semantic.BuiltinType;
import com.github.andreasarvidsson.eld.semantic.ClassDeclarationSymbol;
import com.github.andreasarvidsson.eld.semantic.ClassType;
import com.github.andreasarvidsson.eld.semantic.ConstType;
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
import com.github.andreasarvidsson.eld.semantic.JavaClassSymbol;
import com.github.andreasarvidsson.eld.semantic.InterfaceSymbol;
import com.github.andreasarvidsson.eld.semantic.JavaTypes;
import com.github.andreasarvidsson.eld.semantic.PromiseType;
import com.github.andreasarvidsson.eld.semantic.PromiseSourceType;

/** Generates a Java 21 module named Test and its declared classes. */
public final class BytecodeGenerator {
    private final String moduleName;
    private final String parentName;
    private final int previousItems;
    private final Program program;
    private final SemanticModel semanticModel;
    private final Map<String, String> classOwners;
    private @Nullable ClassBuilder currentWriter;
    private String currentOwner;
    private int nextLambda;
    private int nextObject;
    private int nextAsyncFrame;
    private final IdentityHashMap<FunctionDeclaration, String> asyncFrameNames =
        new IdentityHashMap<>();
    private final List<String> asyncFrameNameOrder = new ArrayList<>();
    private final IdentityHashMap<ObjectExpression, String> objectNames =
        new IdentityHashMap<>();
    private final Map<String, byte[]> objectClasses = new LinkedHashMap<>();
    private final IdentityHashMap<ObjectExpression, ObjectInfo> objects =
        new IdentityHashMap<>();
    private final Map<String, InterfaceType> objectTypes =
        new LinkedHashMap<>();
    private final List<String> objectNameOrder = new ArrayList<>();

    private static List<IdentifierPattern> patternBindings(
        final Pattern pattern
    ) {
        final List<IdentifierPattern> result = new ArrayList<>();
        collectPatternBindings(pattern, result);
        return result;
    }

    private static void collectPatternBindings(
        final Pattern pattern,
        final List<IdentifierPattern> result
    ) {
        if (pattern instanceof IdentifierPattern identifier) {
            result.add(identifier);
        }
        else if (pattern instanceof TuplePattern tuple) {
            tuple.elements()
                .forEach(element -> collectPatternBindings(element, result));
        }
        else if (pattern instanceof RecordPattern record) {
            record.fields()
                .forEach(
                    field -> collectPatternBindings(field.target(), result)
                );
        }
    }

    private void addPatternGlobals(
        final IdentityHashMap<Symbol, String> globals,
        final DestructuringDeclaration declaration
    ) {
        for (final IdentifierPattern binding : patternBindings(
            declaration.pattern()
        )) {
            final Symbol symbol = semanticModel.getPatternSymbol(binding);
            globals.put(symbol, symbol.name());
        }
    }

    private record ObjectInfo(
        String owner, List<Symbol> captures, boolean receiver,
        String constructorDescriptor
    ) {
    }

    private record PatternValue(IdentifierPattern binding, int local) {
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
        this.currentOwner = moduleName;
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
                        || item instanceof RecordDeclaration
                        || item instanceof InterfaceDeclaration
                        || AstTraversal
                            .anyMatch(item, ObjectExpression.class::isInstance)
                        || AstTraversal.anyMatch(
                            item,
                            node -> node instanceof FunctionDeclaration function
                                && function.async()
                                && AstTraversal.anyMatch(
                                    function.body(),
                                    AwaitExpression.class::isInstance
                                )
                        )
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
     * Class fields and methods are instance members unless declared static.
     * Instance field initializers run in source order before the constructor
     * body; const fields are final.
     */
    public Map<String, byte[]> generateClasses() {
        nextLambda = 0;
        nextObject = 0;
        nextAsyncFrame = 0;
        asyncFrameNames.clear();
        asyncFrameNameOrder.clear();
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
                if (
                    node instanceof FunctionDeclaration function
                        && function.async()
                        && AstTraversal.anyMatch(
                            function.body(),
                            AwaitExpression.class::isInstance
                        )
                ) {
                    asyncFrameName(function);
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
            if (item instanceof RecordDeclaration record) {
                final ClassDeclaration declaration =
                    semanticModel.getRecordClass(record);
                final String name = className(declaration);
                if (
                    classes
                        .putIfAbsent(name, generateClass(declaration)) != null
                ) {
                    throw unsupported(
                        record,
                        "Duplicate record " + record.name().name()
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
                        semanticModel.isRecordClass(generatedClass)
                            ? "java/lang/Record"
                            : superclass == null
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
            currentOwner = owner;

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
                else if (item instanceof DestructuringDeclaration pattern) {
                    addPatternGlobals(globals, pattern);
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
                        Visibility.PUBLIC,
                        false
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
        final Type type,
        final boolean delegateToAccessor
    ) {
        generateMethod(
            writer,
            ACC_PUBLIC | ACC_SYNTHETIC,
            "$get$" + name,
            "()" + descriptor(type),
            null,
            getter -> {

                getter.aload(0);
                if (delegateToAccessor) {
                    getter.invoke(
                        INVOKEVIRTUAL,
                        classDesc(fieldOwner),
                        name,
                        MethodTypeDesc.ofDescriptor("()" + descriptor(type)),
                        false
                    );
                }
                else {
                    getter.fieldAccess(
                        GETFIELD,
                        classDesc(fieldOwner),
                        name,
                        ClassDesc.ofDescriptor(descriptor(type))
                    );
                }
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

    private void generateRecordAttribute(
        final ClassBuilder writer,
        final RecordDeclaration record
    ) {
        final List<RecordComponentInfo> components = new ArrayList<>();
        for (final RecordParameter parameter : record.parameters()) {
            final Type type = semanticModel.getResolvedType(parameter.type());
            final String signature = fieldSignature(type);
            components.add(
                RecordComponentInfo.of(
                    parameter.name().name(),
                    ClassDesc.ofDescriptor(descriptor(type)),
                    signature == null
                        ? List.of()
                        : List.of(
                            SignatureAttribute.of(
                                java.lang.classfile.Signature
                                    .parseFrom(signature)
                            )
                        )
                )
            );
        }
        writer.with(RecordAttribute.of(components));
    }

    private void generateRecordAccessors(
        final ClassBuilder writer,
        final String owner,
        final RecordDeclaration record
    ) {
        for (final RecordParameter parameter : record.parameters()) {
            final Type type = semanticModel.getResolvedType(parameter.type());
            generateMethod(
                writer,
                ACC_PUBLIC,
                parameter.name().name(),
                "()" + descriptor(type),
                methodSignature(new FunctionType(List.of(), type)),
                accessor -> {
                    accessor.aload(0);
                    accessor.fieldAccess(
                        GETFIELD,
                        classDesc(owner),
                        parameter.name().name(),
                        ClassDesc.ofDescriptor(descriptor(type))
                    );
                    accessor.with(simpleInstruction(returnOpcode(type)));
                }
            );
        }
    }

    private void generateRecordObjectMethods(
        final ClassBuilder writer,
        final String owner,
        final ClassDeclaration declaration,
        final RecordDeclaration record
    ) {
        if (!declaresRecordMethod(record, "toString", 0)) {
            generateMethod(
                writer,
                ACC_PUBLIC | ACC_FINAL,
                "toString",
                "()Ljava/lang/String;",
                null,
                method -> {
                    method.aload(0);
                    method.invokedynamic(
                        recordObjectCallSite(
                            "toString",
                            MethodTypeDesc.of(
                                classDesc("java/lang/String"),
                                classDesc(owner)
                            ),
                            owner,
                            record
                        )
                    );
                    method.astore(1);
                    method.ldc(record.name().name() + "(");
                    method.aload(1);
                    method.ldc(record.name().name().length() + 1);
                    method.aload(1);
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc("java/lang/String"),
                        "length",
                        MethodTypeDesc.ofDescriptor("()I"),
                        false
                    );
                    method.iconst_1();
                    method.isub();
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc("java/lang/String"),
                        "substring",
                        MethodTypeDesc.ofDescriptor("(II)Ljava/lang/String;"),
                        false
                    );
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc("java/lang/String"),
                        "concat",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/String;)Ljava/lang/String;"
                        ),
                        false
                    );
                    method.ldc(")");
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc("java/lang/String"),
                        "concat",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/String;)Ljava/lang/String;"
                        ),
                        false
                    );
                    method.areturn();
                }
            );
        }
        if (!hasMethod(declaration, "hashCode", "()I")) {
            generateMethod(
                writer,
                ACC_PUBLIC | ACC_FINAL,
                "hashCode",
                "()I",
                null,
                method -> {
                    method.aload(0);
                    method.invokedynamic(
                        recordObjectCallSite(
                            "hashCode",
                            MethodTypeDesc.of(
                                ClassDesc.ofDescriptor("I"),
                                classDesc(owner)
                            ),
                            owner,
                            record
                        )
                    );
                    method.ireturn();
                }
            );
        }
        if (!hasMethod(declaration, "equals", "(Ljava/lang/Object;)Z")) {
            generateMethod(
                writer,
                ACC_PUBLIC | ACC_FINAL,
                "equals",
                "(Ljava/lang/Object;)Z",
                null,
                method -> {
                    method.aload(0);
                    method.aload(1);
                    method.invokedynamic(
                        recordObjectCallSite(
                            "equals",
                            MethodTypeDesc.of(
                                ClassDesc.ofDescriptor("Z"),
                                classDesc(owner),
                                classDesc("java/lang/Object")
                            ),
                            owner,
                            record
                        )
                    );
                    method.ireturn();
                }
            );
        }
    }

    private static boolean declaresRecordMethod(
        final RecordDeclaration record,
        final String name,
        final int parameterCount
    ) {
        return record.methods()
            .stream()
            .map(MemberDeclaration::declaration)
            .filter(FunctionDeclaration.class::isInstance)
            .map(FunctionDeclaration.class::cast)
            .anyMatch(
                method -> method.name().name().equals(name)
                    && method.parameters().size() == parameterCount
            );
    }

    private String methodName(final FunctionSymbol function) {
        final ClassType owner = semanticModel.findClassMemberOwner(function);
        if (owner == null) {
            return function.name();
        }
        final RecordDeclaration record =
            semanticModel.findRecordDeclaration(owner);
        if (
            record != null && record.parameters()
                .stream()
                .anyMatch(
                    parameter -> parameter.name().name().equals(function.name())
                )
        ) {
            return "$" + function.name();
        }
        return function.name();
    }

    private boolean hasMethod(
        final ClassDeclaration declaration,
        final String name,
        final String descriptor
    ) {
        return declaration.members()
            .stream()
            .map(MemberDeclaration::declaration)
            .filter(FunctionDeclaration.class::isInstance)
            .map(FunctionDeclaration.class::cast)
            .anyMatch(
                function -> function.name().name().equals(name)
                    && methodDescriptor(
                        (FunctionType) semanticModel.getSymbol(function.name())
                            .type()
                    ).equals(descriptor)
            );
    }

    private DynamicCallSiteDesc recordObjectCallSite(
        final String methodName,
        final MethodTypeDesc invocationType,
        final String owner,
        final RecordDeclaration record
    ) {
        final ClassDesc recordClass = classDesc(owner);
        final List<ConstantDesc> arguments = new ArrayList<>();
        arguments.add(recordClass);
        arguments.add(
            record.parameters()
                .stream()
                .map(parameter -> parameter.name().name())
                .collect(Collectors.joining(";"))
        );
        for (final RecordParameter parameter : record.parameters()) {
            final Type type = semanticModel.getResolvedType(parameter.type());
            arguments.add(
                MethodHandleDesc.ofField(
                    DirectMethodHandleDesc.Kind.GETTER,
                    recordClass,
                    parameter.name().name(),
                    ClassDesc.ofDescriptor(descriptor(type))
                )
            );
        }
        final DirectMethodHandleDesc bootstrap =
            MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC,
                classDesc("java/lang/runtime/ObjectMethods"),
                "bootstrap",
                MethodTypeDesc.of(
                    classDesc("java/lang/Object"),
                    classDesc("java/lang/invoke/MethodHandles$Lookup"),
                    classDesc("java/lang/String"),
                    classDesc("java/lang/invoke/TypeDescriptor"),
                    classDesc("java/lang/Class"),
                    classDesc("java/lang/String"),
                    ClassDesc.ofDescriptor("[Ljava/lang/invoke/MethodHandle;")
                )
            );
        return DynamicCallSiteDesc.of(
            bootstrap,
            methodName,
            invocationType,
            arguments.toArray(ConstantDesc[]::new)
        );
    }

    private byte[] generateClass(final ClassDeclaration declaration) {
        final String name = className(declaration);
        final ClassType classType =
            (ClassType) semanticModel.getSymbol(declaration.name()).type();
        final boolean recordClass = semanticModel.isRecordClass(declaration);
        final @Nullable RecordDeclaration record =
            recordClass
                ? semanticModel.getRecordDeclaration(declaration)
                : null;
        final ClassType superclass = semanticModel.getSuperclass(classType);
        final String superclassOwner =
            recordClass
                ? "java/lang/Record"
                : superclass == null
                    ? "java/lang/Object"
                    : classOwner(superclass);
        return classFile().build(classDesc(name), writer -> {
            final List<InnerClassInfo> innerClasses = new ArrayList<>();
            final List<ClassDesc> nestMembers = new ArrayList<>();
            writer.withVersion(JAVA_21_VERSION, 0);
            writer.withFlags(
                ACC_PUBLIC | ACC_SUPER | (recordClass ? ACC_FINAL : 0)
            );
            writer.withSuperclass(classDesc(superclassOwner));
            if (record != null) {
                generateRecordAttribute(writer, record);
            }
            generateClassSignature(
                writer,
                classSignature(
                    superclassOwner,
                    semanticModel.getImplementedInterfaces(classType)
                )
            );
            writer.withInterfaceSymbols(
                semanticModel.getImplementedInterfaces(classType)
                    .stream()
                    .map(BytecodeGenerator.this::interfaceOwner)
                    .map(BytecodeGenerator::classDesc)
                    .toList()
            );

            currentWriter = writer;
            currentOwner = name;

            writer.with(NestHostAttribute.of(classDesc(moduleName)));
            innerClasses.add(
                InnerClassInfo.of(
                    classDesc(name),
                    Optional.of(classDesc(moduleName)),
                    Optional.of(declaration.name().name()),
                    ACC_PUBLIC | ACC_STATIC | (recordClass ? ACC_FINAL : 0)
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
                else if (item instanceof DestructuringDeclaration pattern) {
                    addPatternGlobals(globals, pattern);
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
                    if (!memberDeclaration.staticMember()) {
                        members.put(symbol, symbol.name());
                    }
                    generateField(
                        writer,
                        recordClass
                            ? ACC_PRIVATE | ACC_FINAL
                            : visibilityAccess(memberDeclaration.visibility())
                                | (memberDeclaration.staticMember()
                                    ? ACC_STATIC
                                    : 0)
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
                    if (!memberDeclaration.staticMember()) {
                        members
                            .put(symbol, methodName((FunctionSymbol) symbol));
                    }
                }
                else if (
                    member instanceof UninitializedVariableDeclaration field
                ) {
                    final Symbol symbol = semanticModel.getSymbol(field.name());
                    if (!memberDeclaration.staticMember()) {
                        members.put(symbol, symbol.name());
                    }
                    generateField(
                        writer,
                        recordClass
                            ? ACC_PRIVATE | ACC_FINAL
                            : visibilityAccess(memberDeclaration.visibility())
                                | (memberDeclaration.staticMember()
                                    ? ACC_STATIC
                                    : 0)
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
            if (record != null) {
                generateRecordAccessors(writer, name, record);
                generateRecordObjectMethods(writer, name, declaration, record);
            }
            for (final var entry : semanticModel.getInterfaceFields(classType)
                .entrySet()) {
                final VariableSymbol field = entry.getValue();
                generateGetter(
                    writer,
                    entry.getKey(),
                    classOwner(semanticModel.getClassMemberOwner(field)),
                    field.type(),
                    recordClass
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
            final List<BlockItem> staticInitializers =
                declaration.members()
                    .stream()
                    .filter(MemberDeclaration::staticMember)
                    .map(MemberDeclaration::declaration)
                    .<BlockItem>map(member -> switch (member) {
                        case VariableDeclaration field -> field;
                        case StaticInitializerDeclaration initializer ->
                            initializer.body();
                        default -> null;
                    })
                    .filter(Objects::nonNull)
                    .toList();
            if (!staticInitializers.isEmpty()) {
                generateMethod(
                    writer,
                    ACC_STATIC,
                    "<clinit>",
                    "()V",
                    null,
                    method -> {
                        final MethodGenerator initializer =
                            new MethodGenerator(
                                method,
                                globals,
                                BuiltinType.VOID,
                                null
                            );
                        for (final BlockItem item : staticInitializers) {
                            initializer.item(item);
                        }
                        method.return_();
                    }
                );
            }
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
                    initializer.constructorSuperclassOwner = superclassOwner;
                    initializer.constructorFields =
                        declaration.members()
                            .stream()
                            .filter(member -> !member.staticMember())
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
                    ),
                    false
                );
            }
            for (final MemberDeclaration memberDeclaration : declaration
                .members()) {
                final Declaration member = memberDeclaration.declaration();
                if (member instanceof FunctionDeclaration function) {
                    if (
                        record == null
                            || declaresRecordMethod(
                                record,
                                function.name().name(),
                                function.parameters().size()
                            )
                            || !function.name().name().equals("toString")
                            || !function.parameters().isEmpty()
                    ) {
                        generateFunction(
                            writer,
                            function,
                            globals,
                            memberDeclaration.staticMember() ? null : instance
                        );
                        generateOverrideBridges(writer, name, function);
                    }
                }
            }
            generateInterfaceBridges(writer, name, classType);
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

    private record AsyncStateMachine(
        String owner, String promiseOwner, String resumeName,
        String resumeDescriptor,
        IdentityHashMap<AwaitExpression, Integer> states,
        IdentityHashMap<AwaitExpression, Label> labels,
        IdentityHashMap<AwaitExpression, Boolean> discarded,
        IdentityHashMap<Expression, Integer> spills,
        IdentityHashMap<ForEachStatement, Integer> loopIndexes
    ) {
    }

    private String asyncFrameName(final FunctionDeclaration function) {
        final String existing = asyncFrameNames.get(function);
        if (existing != null) {
            return existing;
        }
        final String name = moduleName + "$$async" + nextAsyncFrame++;
        asyncFrameNames.put(function, name);
        asyncFrameNameOrder.add(name);
        return name;
    }

    private static Expression unwrapGrouping(final Expression expression) {
        return expression instanceof GroupingExpression grouping
            ? unwrapGrouping(grouping.expression())
            : expression;
    }

    private void addAsyncSpill(
        final IdentityHashMap<Expression, Integer> spills,
        final Expression expression
    ) {
        spills.computeIfAbsent(expression, ignored -> spills.size());
    }

    private static boolean containsAwaitNode(final AstNode node) {
        return AstTraversal.anyMatch(node, AwaitExpression.class::isInstance);
    }

    private static boolean hasLaterAwait(
        final List<Expression> operands,
        final int index
    ) {
        return operands.subList(index + 1, operands.size())
            .stream()
            .anyMatch(BytecodeGenerator::containsAwaitNode);
    }

    private List<Expression> asyncCallOperands(final CallExpression call) {
        final List<Expression> operands = new ArrayList<>();
        if (semanticModel.getPromiseMethod(call) == null) {
            final Expression callee = unwrapGrouping(call.callee());
            if (callee instanceof MemberExpression member) {
                final Symbol memberSymbol =
                    semanticModel.getReference(member.member());
                final boolean staticMethod =
                    (memberSymbol instanceof JavaMethodSymbol javaMethod
                        && java.lang.reflect.Modifier
                            .isStatic(javaMethod.method().getModifiers()))
                        || semanticModel.isStaticMember(memberSymbol);
                if (!staticMethod) {
                    operands.add(member.target());
                }
            }
            else if (!(callee instanceof IdentifierExpression)) {
                operands.add(call.callee());
            }
        }
        for (final Expression supplied : call.arguments()) {
            operands.add(
                supplied instanceof NamedArgumentExpression named
                    ? named.value()
                    : supplied
            );
        }
        return operands;
    }

    private List<Expression> asyncCompoundOperands(
        final Expression expression
    ) {
        final List<Expression> operands = new ArrayList<>();
        switch (expression) {
            case FormatStringExpression format ->
                operands.addAll(format.parts());
            case TupleExpression tuple -> operands.addAll(tuple.elements());
            case ArrayExpression array -> {
                for (final Expression element : array.elements()) {
                    operands.add(
                        element instanceof ArraySpread spread
                            ? spread.expression()
                            : element
                    );
                }
            }
            case MapExpression map -> {
                for (final MapElement element : map.elements()) {
                    if (element instanceof MapEntry entry) {
                        operands.add(entry.key());
                        operands.add(entry.value());
                    }
                    else {
                        operands.add(((MapSpread) element).expression());
                    }
                }
            }
            case ObjectExpression object -> {
                final InterfaceContract contract =
                    semanticModel.getInterface(
                        (InterfaceType) semanticModel.getExpressionType(object)
                    );
                for (final ObjectEntry entry : semanticModel
                    .getObjectEvaluation(object)) {
                    if (entry instanceof ObjectSpread spread) {
                        operands.add(spread.value());
                        continue;
                    }
                    final ObjectMember member = (ObjectMember) entry;
                    if (
                        (semanticModel.isSpreadMethod(member)
                            && !contract.fields()
                                .containsKey(member.name().name()))
                            || (contract.methods()
                                .containsKey(member.name().name())
                                && unwrapGrouping(
                                    member.value()
                                ) instanceof LambdaExpression)
                    ) {
                        continue;
                    }
                    operands.add(member.value());
                }
            }
            case NewExpression creation -> {
                for (final Expression supplied : creation.arguments()) {
                    operands.add(
                        supplied instanceof NamedArgumentExpression named
                            ? named.value()
                            : supplied
                    );
                }
            }
            case SubscriptExpression index -> {
                operands.add(index.target());
                operands.add(index.index());
            }
            case SliceExpression slice -> {
                operands.add(slice.target());
                if (slice.startIndex() != null) {
                    operands.add(slice.startIndex());
                }
                if (slice.endIndex() != null) {
                    operands.add(slice.endIndex());
                }
            }
            case AssignmentExpression assignment -> {
                final Expression target = unwrapGrouping(assignment.target());
                if (
                    target instanceof MemberExpression member
                        && !semanticModel.isStaticMember(
                            semanticModel.getReference(member.member())
                        )
                ) {
                    operands.add(member.target());
                }
                else if (target instanceof SubscriptExpression index) {
                    operands.add(index.target());
                    operands.add(index.index());
                }
                operands.add(assignment.value());
            }
            case UnaryExpression unary -> {
                final Expression target = unwrapGrouping(unary.operand());
                if (
                    target instanceof MemberExpression member
                        && !semanticModel.isStaticMember(
                            semanticModel.getReference(member.member())
                        )
                ) {
                    operands.add(member.target());
                }
                else if (target instanceof SubscriptExpression index) {
                    operands.add(index.target());
                    operands.add(index.index());
                }
            }
            case PostfixExpression postfix -> {
                final Expression target = unwrapGrouping(postfix.operand());
                if (
                    target instanceof MemberExpression member
                        && !semanticModel.isStaticMember(
                            semanticModel.getReference(member.member())
                        )
                ) {
                    operands.add(member.target());
                }
                else if (target instanceof SubscriptExpression index) {
                    operands.add(index.target());
                    operands.add(index.index());
                }
            }
            default -> {
                // The expression does not require operand preservation.
            }
        }
        return operands;
    }

    private void collectAsyncStorage(
        final BlockStatement body,
        final IdentityHashMap<Expression, Integer> spills,
        final IdentityHashMap<ForEachStatement, Integer> loopIndexes
    ) {
        AstTraversal.walk(body, node -> {
            if (
                node instanceof ForEachStatement loop && AstTraversal
                    .anyMatch(loop.body(), AwaitExpression.class::isInstance)
            ) {
                loopIndexes
                    .computeIfAbsent(loop, ignored -> loopIndexes.size());
                addAsyncSpill(spills, loop.iterable());
            }
            if (
                !(node instanceof Expression expression) || !AstTraversal
                    .anyMatch(expression, AwaitExpression.class::isInstance)
            ) {
                return;
            }
            if (expression instanceof CallExpression call) {
                final List<Expression> operands = asyncCallOperands(call);
                for (int i = 0; i < operands.size(); i++) {
                    if (hasLaterAwait(operands, i)) {
                        addAsyncSpill(spills, operands.get(i));
                    }
                }
                return;
            }
            if (expression instanceof MapExpression map) {
                addAsyncSpill(spills, map);
                for (final MapElement element : map.elements()) {
                    if (
                        element instanceof MapEntry entry
                            && containsAwaitNode(entry.value())
                    ) {
                        addAsyncSpill(spills, entry.key());
                    }
                }
                return;
            }
            if (
                expression instanceof BinaryExpression binary && AstTraversal
                    .anyMatch(binary.right(), AwaitExpression.class::isInstance)
            ) {
                addAsyncSpill(spills, binary.left());
            }
            final List<Expression> operands = asyncCompoundOperands(expression);
            for (int i = 0; i < operands.size(); i++) {
                if (hasLaterAwait(operands, i)) {
                    addAsyncSpill(spills, operands.get(i));
                }
            }
        });
    }

    private boolean liveAcrossAwait(
        final Symbol symbol,
        final AstNode declaration,
        final Position definition,
        final BlockStatement body,
        final List<AwaitExpression> awaits
    ) {
        final boolean[] live = {false};
        AstTraversal.walk(body, node -> {
            if (
                live[0] || !(node instanceof IdentifierExpression identifier)
                    || !Objects
                        .equals(semanticModel.findReference(identifier), symbol)
            ) {
                return;
            }
            for (final AwaitExpression awaited : awaits) {
                if (
                    definition.compareTo(awaited.range().start()) <= 0
                        && awaited.range()
                            .end()
                            .compareTo(identifier.range().start()) <= 0
                ) {
                    live[0] = true;
                    return;
                }
            }
        });
        if (live[0]) {
            return true;
        }
        AstTraversal.walk(body, node -> {
            if (live[0] || !(node instanceof Statement loop)) {
                return;
            }
            final List<AstNode> repeated = switch (loop) {
                case WhileStatement statement ->
                    List.of(statement.condition(), statement.body());
                case DoWhileStatement statement ->
                    List.of(statement.body(), statement.condition());
                case ForStatement statement -> {
                    final List<AstNode> nodes = new ArrayList<>();
                    if (statement.condition() != null) {
                        nodes.add(statement.condition());
                    }
                    nodes.add(statement.body());
                    if (statement.update() != null) {
                        nodes.add(statement.update());
                    }
                    yield nodes;
                }
                case ForEachStatement statement -> List.of(statement.body());
                default -> List.of();
            };
            if (
                repeated.isEmpty()
                    || (loop instanceof ForEachStatement
                        && Objects.equals(declaration, loop))
                    || repeated.stream()
                        .anyMatch(
                            part -> AstTraversal.anyMatch(
                                part,
                                child -> Objects.equals(child, declaration)
                            )
                        )
                    || repeated.stream()
                        .noneMatch(
                            part -> AstTraversal.anyMatch(
                                part,
                                AwaitExpression.class::isInstance
                            )
                        )
            ) {
                return;
            }
            live[0] =
                repeated.stream()
                    .anyMatch(
                        part -> AstTraversal.anyMatch(
                            part,
                            child -> child instanceof IdentifierExpression identifier
                                && Objects.equals(
                                    semanticModel.findReference(identifier),
                                    symbol
                                )
                        )
                    );
        });
        return live[0];
    }

    private void generateFunction(
        final ClassBuilder writer,
        final FunctionDeclaration function,
        final IdentityHashMap<Symbol, String> globals,
        final @Nullable InstanceContext instance
    ) {
        final FunctionSymbol symbol =
            (FunctionSymbol) semanticModel.getSymbol(function.name());
        final boolean classMember =
            semanticModel.findClassMemberOwner(symbol) != null;
        if (function.async()) {
            generateAsyncFunction(writer, function, symbol, globals, instance);
            generateDefaultOverload(
                writer,
                methodName(symbol),
                symbol.type(),
                function.parameters(),
                globals,
                instance,
                classMember
                    ? semanticModel.getMemberVisibility(symbol)
                    : Visibility.PUBLIC,
                function.finalMethod()
            );
            return;
        }
        generateMethod(
            writer,
            visibilityAccess(
                classMember
                    ? semanticModel.getMemberVisibility(symbol)
                    : Visibility.PUBLIC
            ) | (instance == null ? ACC_STATIC : 0)
                | (function.finalMethod() ? ACC_FINAL : 0),
            methodName(symbol),
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
            methodName(symbol),
            symbol.type(),
            function.parameters(),
            globals,
            instance,
            classMember
                ? semanticModel.getMemberVisibility(symbol)
                : Visibility.PUBLIC,
            function.finalMethod()
        );
    }

    private void generateAsyncFunction(
        final ClassBuilder writer,
        final FunctionDeclaration function,
        final FunctionSymbol symbol,
        final IdentityHashMap<Symbol, String> globals,
        final @Nullable InstanceContext instance
    ) {
        final Type resultType =
            Objects.requireNonNull(semanticModel.getAsyncResultType(symbol));
        final List<AwaitExpression> awaits = new ArrayList<>();
        AstTraversal.walk(function.body(), node -> {
            if (node instanceof AwaitExpression awaited) {
                awaits.add(awaited);
            }
        });
        final IdentityHashMap<Expression, Integer> spills =
            new IdentityHashMap<>();
        final IdentityHashMap<ForEachStatement, Integer> loopIndexes =
            new IdentityHashMap<>();
        collectAsyncStorage(function.body(), spills, loopIndexes);
        if (!awaits.isEmpty()) {
            generateSuspendingAsyncFunction(
                writer,
                function,
                symbol,
                globals,
                instance,
                resultType,
                awaits,
                spills,
                loopIndexes
            );
            return;
        }
        final FunctionType bodyType =
            new FunctionType(symbol.type().parameterTypes(), resultType);
        final String bodyName = "$async$" + methodName(symbol);
        final int staticFlag = instance == null ? ACC_STATIC : 0;
        generateMethod(
            writer,
            ACC_PRIVATE | ACC_SYNTHETIC | staticFlag,
            bodyName,
            methodDescriptor(bodyType),
            methodSignature(bodyType),
            method -> {
                final MethodGenerator generator =
                    new MethodGenerator(method, globals, resultType, instance);
                for (final FunctionParameter parameter : function
                    .parameters()) {
                    generator.local(semanticModel.getSymbol(parameter.name()));
                }
                generator.finish(generator.block(function.body()));
            }
        );
        generateMethod(
            writer,
            visibilityAccess(
                semanticModel.findClassMemberOwner(symbol) != null
                    ? semanticModel.getMemberVisibility(symbol)
                    : Visibility.PUBLIC
            ) | staticFlag | (function.finalMethod() ? ACC_FINAL : 0),
            methodName(symbol),
            methodDescriptor(symbol.type()),
            methodSignature(symbol.type()),
            method -> {
                final String sourceOwner =
                    "com/github/andreasarvidsson/eld/runtime/PromiseSource";
                int parameterSlot = instance == null ? 0 : 1;
                final int sourceSlot =
                    parameterSlot + bodyType.parameterTypes()
                        .stream()
                        .mapToInt(BytecodeGenerator::slots)
                        .sum();
                method.new_(classDesc(sourceOwner));
                method.dup();
                method.invoke(
                    INVOKESPECIAL,
                    classDesc(sourceOwner),
                    "<init>",
                    MethodTypeDesc.ofDescriptor("()V"),
                    false
                );
                method.astore(sourceSlot);
                final Label start = method.newLabel();
                final Label end = method.newLabel();
                final Label handler = method.newLabel();
                final Label complete = method.newLabel();
                method.labelBinding(start);
                method.aload(sourceSlot);
                if (instance != null) {
                    method.aload(0);
                }
                for (final Type parameter : bodyType.parameterTypes()) {
                    method.with(
                        localInstruction(loadOpcode(parameter), parameterSlot)
                    );
                    parameterSlot += slots(parameter);
                }
                method.invoke(
                    instance == null ? INVOKESTATIC : INVOKESPECIAL,
                    classDesc(
                        instance == null ? currentOwner : instance.owner()
                    ),
                    bodyName,
                    MethodTypeDesc.ofDescriptor(methodDescriptor(bodyType)),
                    false
                );
                if (resultType != BuiltinType.VOID) {
                    boxValue(method, resultType);
                }
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(sourceOwner),
                    "resolve",
                    MethodTypeDesc.ofDescriptor(
                        resultType == BuiltinType.VOID
                            ? "()V"
                            : "(Ljava/lang/Object;)V"
                    ),
                    false
                );
                method.labelBinding(end);
                method.branch(GOTO, complete);
                method.labelBinding(handler);
                final int errorSlot = sourceSlot + 1;
                method.astore(errorSlot);
                method.aload(sourceSlot);
                method.aload(errorSlot);
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(sourceOwner),
                    "reject",
                    MethodTypeDesc.ofDescriptor("(Ljava/lang/Throwable;)V"),
                    false
                );
                method.labelBinding(complete);
                method.aload(sourceSlot);
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(sourceOwner),
                    "promise",
                    MethodTypeDesc.ofDescriptor(
                        "()Lcom/github/andreasarvidsson/eld/runtime/EldPromise;"
                    ),
                    false
                );
                method.areturn();
                method.exceptionCatchAll(start, end, handler);
            }
        );
    }

    private void generateSuspendingAsyncFunction(
        final ClassBuilder writer,
        final FunctionDeclaration function,
        final FunctionSymbol symbol,
        final IdentityHashMap<Symbol, String> globals,
        final @Nullable InstanceContext instance,
        final Type resultType,
        final List<AwaitExpression> awaits,
        final IdentityHashMap<Expression, Integer> spills,
        final IdentityHashMap<ForEachStatement, Integer> loopIndexes
    ) {
        final String sourceOwner =
            "com/github/andreasarvidsson/eld/runtime/PromiseSource";
        final String promiseOwner =
            "com/github/andreasarvidsson/eld/runtime/EldPromise";
        final String frameOwner = asyncFrameName(function);
        final String resumeName = "resume";
        final String resumeDescriptor =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Throwable;)V";
        final IdentityHashMap<Symbol, String> fields = new IdentityHashMap<>();
        int fieldIndex = 0;
        for (final FunctionParameter parameter : function.parameters()) {
            final Symbol parameterSymbol =
                semanticModel.getSymbol(parameter.name());
            fields.put(parameterSymbol, "$local" + fieldIndex++);
        }
        final int[] nextField = {fieldIndex};
        AstTraversal.walk(function.body(), node -> {
            if (node instanceof DestructuringDeclaration destructuring) {
                for (final IdentifierPattern binding : patternBindings(
                    destructuring.pattern()
                )) {
                    final Symbol bindingSymbol =
                        semanticModel.getPatternSymbol(binding);
                    if (
                        !fields.containsKey(bindingSymbol) && liveAcrossAwait(
                            bindingSymbol,
                            destructuring,
                            destructuring.initializer().range().end(),
                            function.body(),
                            awaits
                        )
                    ) {
                        fields.put(bindingSymbol, "$local" + nextField[0]++);
                    }
                }
                return;
            }
            final Symbol local;
            final Position definition;
            if (node instanceof VariableDeclaration variable) {
                local = semanticModel.getSymbol(variable.name());
                definition = variable.initializer().range().end();
            }
            else if (node instanceof CatchClause clause) {
                local = semanticModel.getSymbol(clause.name());
                definition = clause.name().range().end();
            }
            else if (node instanceof ForEachStatement loop) {
                local = semanticModel.getSymbol(loop.value());
                definition = loop.iterable().range().end();
            }
            else {
                return;
            }
            if (
                !fields.containsKey(local) && liveAcrossAwait(
                    local,
                    node,
                    definition,
                    function.body(),
                    awaits
                )
            ) {
                fields.put(local, "$local" + nextField[0]++);
            }
            if (node instanceof ForEachStatement loop && loop.index() != null) {
                final Symbol index = semanticModel.getSymbol(loop.index());
                if (
                    liveAcrossAwait(
                        index,
                        loop,
                        loop.iterable().range().end(),
                        function.body(),
                        awaits
                    )
                ) {
                    fields.computeIfAbsent(
                        index,
                        ignored -> "$local" + nextField[0]++
                    );
                }
            }
        });

        final ClassBuilder savedWriter = currentWriter;
        try {
            objectClasses.put(
                frameOwner,
                generateAsyncFrame(
                    frameOwner,
                    sourceOwner,
                    promiseOwner,
                    resumeName,
                    resumeDescriptor,
                    function,
                    globals,
                    instance,
                    resultType,
                    awaits,
                    fields,
                    spills,
                    loopIndexes
                )
            );
        }
        finally {
            currentWriter = savedWriter;
        }

        generateMethod(
            writer,
            visibilityAccess(
                semanticModel.findClassMemberOwner(symbol) != null
                    ? semanticModel.getMemberVisibility(symbol)
                    : Visibility.PUBLIC
            ) | (instance == null ? ACC_STATIC : 0)
                | (function.finalMethod() ? ACC_FINAL : 0),
            methodName(symbol),
            methodDescriptor(symbol.type()),
            methodSignature(symbol.type()),
            method -> {
                int parameterSlot = instance == null ? 0 : 1;
                final int frameSlot =
                    parameterSlot + symbol.type()
                        .parameterTypes()
                        .stream()
                        .mapToInt(BytecodeGenerator::slots)
                        .sum();
                method.new_(classDesc(frameOwner));
                method.dup();
                method.invoke(
                    INVOKESPECIAL,
                    classDesc(frameOwner),
                    "<init>",
                    MethodTypeDesc.ofDescriptor("()V"),
                    false
                );
                method.astore(frameSlot);
                if (instance != null) {
                    method.aload(frameSlot);
                    method.aload(0);
                    method.fieldAccess(
                        PUTFIELD,
                        classDesc(frameOwner),
                        "$receiver",
                        ClassDesc.ofDescriptor("L" + instance.owner() + ";")
                    );
                }
                for (final FunctionParameter parameter : function
                    .parameters()) {
                    final Symbol parameterSymbol =
                        semanticModel.getSymbol(parameter.name());
                    method.aload(frameSlot);
                    method.with(
                        localInstruction(
                            loadOpcode(parameterSymbol.type()),
                            parameterSlot
                        )
                    );
                    method.fieldAccess(
                        PUTFIELD,
                        classDesc(frameOwner),
                        Objects.requireNonNull(fields.get(parameterSymbol)),
                        ClassDesc
                            .ofDescriptor(descriptor(parameterSymbol.type()))
                    );
                    parameterSlot += slots(parameterSymbol.type());
                }
                method.aload(frameSlot);
                method.aconst_null();
                method.aconst_null();
                method.invoke(
                    INVOKESTATIC,
                    classDesc(frameOwner),
                    resumeName,
                    MethodTypeDesc.ofDescriptor(resumeDescriptor),
                    false
                );
                method.aload(frameSlot);
                method.fieldAccess(
                    GETFIELD,
                    classDesc(frameOwner),
                    "$source",
                    ClassDesc.ofDescriptor("L" + sourceOwner + ";")
                );
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(sourceOwner),
                    "promise",
                    MethodTypeDesc.ofDescriptor("()L" + promiseOwner + ";"),
                    false
                );
                method.areturn();
            }
        );
    }

    private byte[] generateAsyncFrame(
        final String frameOwner,
        final String sourceOwner,
        final String promiseOwner,
        final String resumeName,
        final String resumeDescriptor,
        final FunctionDeclaration function,
        final IdentityHashMap<Symbol, String> globals,
        final @Nullable InstanceContext lexicalInstance,
        final Type resultType,
        final List<AwaitExpression> awaits,
        final IdentityHashMap<Symbol, String> fields,
        final IdentityHashMap<Expression, Integer> spills,
        final IdentityHashMap<ForEachStatement, Integer> loopIndexes
    ) {
        return classFile().build(classDesc(frameOwner), frame -> {
            frame.withVersion(JAVA_21_VERSION, 0);
            frame.withFlags(ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC);
            frame.withSuperclass(classDesc("java/lang/Object"));
            currentWriter = frame;
            frame.with(NestHostAttribute.of(classDesc(moduleName)));

            generateField(
                frame,
                ACC_FINAL | ACC_SYNTHETIC,
                "$source",
                "L" + sourceOwner + ";",
                null,
                null
            );
            generateField(frame, ACC_SYNTHETIC, "$state", "I", null, null);
            if (lexicalInstance != null) {
                generateField(
                    frame,
                    ACC_SYNTHETIC,
                    "$receiver",
                    "L" + lexicalInstance.owner() + ";",
                    null,
                    null
                );
            }
            for (final var entry : fields.entrySet()
                .stream()
                .sorted(
                    Comparator.comparingInt(
                        entry -> Integer.parseInt(
                            entry.getValue().substring("$local".length())
                        )
                    )
                )
                .toList()) {
                generateField(
                    frame,
                    ACC_SYNTHETIC,
                    entry.getValue(),
                    descriptor(entry.getKey().type()),
                    null,
                    null
                );
            }
            if (!spills.isEmpty()) {
                generateField(
                    frame,
                    ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC,
                    "$spills",
                    "[Ljava/lang/Object;",
                    null,
                    null
                );
            }
            if (!loopIndexes.isEmpty()) {
                generateField(
                    frame,
                    ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC,
                    "$loopIndexes",
                    "[I",
                    null,
                    null
                );
            }
            generateMethod(frame, 0, "<init>", "()V", null, constructor -> {
                constructor.aload(0);
                constructor.invoke(
                    INVOKESPECIAL,
                    classDesc("java/lang/Object"),
                    "<init>",
                    MethodTypeDesc.ofDescriptor("()V"),
                    false
                );
                constructor.aload(0);
                constructor.new_(classDesc(sourceOwner));
                constructor.dup();
                constructor.invoke(
                    INVOKESPECIAL,
                    classDesc(sourceOwner),
                    "<init>",
                    MethodTypeDesc.ofDescriptor("()V"),
                    false
                );
                constructor.fieldAccess(
                    PUTFIELD,
                    classDesc(frameOwner),
                    "$source",
                    ClassDesc.ofDescriptor("L" + sourceOwner + ";")
                );
                if (!spills.isEmpty()) {
                    constructor.aload(0);
                    constructor.ldc(spills.size());
                    constructor.anewarray(classDesc("java/lang/Object"));
                    constructor.fieldAccess(
                        PUTFIELD,
                        classDesc(frameOwner),
                        "$spills",
                        ClassDesc.ofDescriptor("[Ljava/lang/Object;")
                    );
                }
                if (!loopIndexes.isEmpty()) {
                    constructor.aload(0);
                    constructor.ldc(loopIndexes.size());
                    constructor.newarray(TypeKind.INT);
                    constructor.fieldAccess(
                        PUTFIELD,
                        classDesc(frameOwner),
                        "$loopIndexes",
                        ClassDesc.ofDescriptor("[I")
                    );
                }
                constructor.return_();
            });
            generateMethod(
                frame,
                ACC_STATIC | ACC_SYNTHETIC,
                resumeName,
                resumeDescriptor,
                null,
                method -> {
                    method.aload(0);
                    method.checkcast(classDesc(frameOwner));
                    method.astore(0);
                    final IdentityHashMap<AwaitExpression, Label> labels =
                        new IdentityHashMap<>();
                    for (final AwaitExpression awaited : awaits) {
                        labels.put(awaited, method.newLabel());
                    }
                    final Label initial = method.newLabel();
                    final Label invalidState = method.newLabel();
                    method.aload(0);
                    method.fieldAccess(
                        GETFIELD,
                        classDesc(frameOwner),
                        "$state",
                        ClassDesc.ofDescriptor("I")
                    );
                    final List<SwitchCase> states = new ArrayList<>();
                    states.add(SwitchCase.of(0, initial));
                    for (int i = 0; i < awaits.size(); i++) {
                        states.add(
                            SwitchCase.of(
                                i + 1,
                                Objects
                                    .requireNonNull(labels.get(awaits.get(i)))
                            )
                        );
                    }
                    method.tableswitch(0, awaits.size(), invalidState, states);
                    method.labelBinding(invalidState);
                    method.new_(classDesc("java/lang/IllegalStateException"));
                    method.dup();
                    method.ldc("Invalid async state");
                    method.invoke(
                        INVOKESPECIAL,
                        classDesc("java/lang/IllegalStateException"),
                        "<init>",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"),
                        false
                    );
                    method.athrow();

                    final Label start = method.newLabel();
                    final Label end = method.newLabel();
                    final Label handler = method.newLabel();
                    method.labelBinding(start);
                    method.labelBinding(initial);
                    final MethodGenerator generator =
                        new MethodGenerator(
                            method,
                            globals,
                            BuiltinType.VOID,
                            new InstanceContext(frameOwner, fields)
                        );
                    generator.reserveLocals(3);
                    generator.asyncCompletionField(frameOwner, resultType);
                    generator.asyncStateMachine(
                        frameOwner,
                        promiseOwner,
                        resumeName,
                        resumeDescriptor,
                        function.body(),
                        awaits,
                        labels,
                        spills,
                        loopIndexes
                    );
                    if (lexicalInstance != null) {
                        generator.lexicalInstance(lexicalInstance);
                    }
                    generator.finish(generator.block(function.body()));
                    method.labelBinding(end);
                    method.exceptionCatchAll(start, end, handler);
                    method.labelBinding(handler);
                    final int failure = generator.reserve(BuiltinType.ANY);
                    method.astore(failure);
                    generator.loadAsyncSource();
                    method.aload(failure);
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc(sourceOwner),
                        "reject",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/Throwable;)V"),
                        false
                    );
                    method.return_();
                }
            );
        });
    }

    private static boolean discardedAwait(
        final BlockItem item,
        final AwaitExpression awaited
    ) {
        final boolean[] discarded = {false};
        AstTraversal.walk(item, node -> {
            if (
                (node instanceof ExpressionStatement statement
                    && Objects.equals(statement.expression(), awaited))
                    || (node instanceof IgnoreStatement ignored
                        && Objects.equals(ignored.expression(), awaited))
            ) {
                discarded[0] = true;
            }
        });
        return discarded[0];
    }

    private void boxValue(final CodeBuilder method, final Type type) {
        final String owner = boxedOwner(type);
        if (owner != null) {
            method.invoke(
                INVOKESTATIC,
                classDesc(owner),
                "valueOf",
                MethodTypeDesc
                    .ofDescriptor("(" + descriptor(type) + ")L" + owner + ";"),
                false
            );
        }
    }

    private String defaultDescriptor(final FunctionType type) {
        return methodDescriptor(type)
            .replace(")", (longOmissionMask(type) ? "J" : "I") + ")");
    }

    private static boolean longOmissionMask(final FunctionType type) {
        final int parameters = type.parameterTypes().size();
        if (parameters > Long.SIZE) {
            throw new BytecodeException(
                "Default arguments support at most %d parameters",
                Long.SIZE
            );
        }
        return parameters > Integer.SIZE;
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
        final Visibility visibility,
        final boolean finalMethod
    ) {
        if (parameters.stream().noneMatch(FunctionParameter::omittable)) {
            return;
        }
        generateMethod(
            writer,
            visibilityAccess(visibility) | ACC_SYNTHETIC
                | (instance == null ? ACC_STATIC : 0)
                | (finalMethod ? ACC_FINAL : 0),
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
                final boolean longMask = longOmissionMask(type);
                final int mask = generator.nextLocal;
                generator.nextLocal += longMask ? 2 : 1;
                for (int i = 0; i < parameters.size(); i++) {
                    final FunctionParameter parameter = parameters.get(i);
                    if (!parameter.omittable()) {
                        continue;
                    }
                    final Label supplied = method.newLabel();
                    if (longMask) {
                        method.lload(mask);
                        method.ldc(1L << i);
                        method.land();
                        method.lconst_0();
                        method.lcmp();
                    }
                    else {
                        method.iload(mask);
                        method.ldc(1 << i);
                        method.iand();
                    }
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
                    classDesc(
                        instance == null ? currentOwner : instance.owner()
                    ),
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
            currentOwner = moduleName;
            for (final String objectName : objectNameOrder) {
                nestMembers.add(classDesc(objectName));
            }
            for (final String asyncFrameName : asyncFrameNameOrder) {
                nestMembers.add(classDesc(asyncFrameName));
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
                else if (item instanceof DestructuringDeclaration pattern) {
                    addPatternGlobals(globals, pattern);
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
                else if (item instanceof DestructuringDeclaration pattern) {
                    initializers.add(item);
                    for (final IdentifierPattern binding : patternBindings(
                        pattern.pattern()
                    )) {
                        final Symbol symbol =
                            semanticModel.getPatternSymbol(binding);
                        globals.put(symbol, symbol.name());
                        generateField(
                            writer,
                            ACC_PUBLIC | ACC_STATIC
                                | (moduleName.equals("Test")
                                    && pattern.mutability() == Mutability.CONST
                                        ? ACC_FINAL
                                        : 0),
                            symbol.name(),
                            descriptor(symbol.type()),
                            fieldSignature(symbol.type()),
                            null
                        );
                    }
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
                else if (item instanceof RecordDeclaration record) {
                    final String name =
                        className(semanticModel.getRecordClass(record));
                    nestMembers.add(classDesc(name));
                    innerClasses.add(
                        InnerClassInfo.of(
                            classDesc(name),
                            Optional.of(classDesc(moduleName)),
                            Optional.of(record.name().name()),
                            ACC_PUBLIC | ACC_STATIC | ACC_FINAL
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
                else if (
                    !(item instanceof FunctionDeclaration
                        || item instanceof TypeAliasDeclaration)
                ) {
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
                case INSTANCEOF -> null;
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
        final Type unqualified = ConstType.unwrap(type);
        return unqualified instanceof ClassType cls
            ? classOwner(cls)
            : interfaceOwner((InterfaceType) unqualified);
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
            case TupleType _ ->
                "Lcom/github/andreasarvidsson/eld/runtime/EldTuple;";
            case UnionType union -> unionDescriptor(union);
            case FunctionType _ -> "Ljava/lang/invoke/MethodHandle;";
            case BuiltinFunctionType builtin ->
                builtin == BuiltinFunctionType.PRINT
                    ? "Ljava/io/PrintStream;"
                    : "Ljava/lang/invoke/MethodHandle;";
            case ClassType classType -> "L" + classOwner(classType) + ";";
            case InterfaceType contract -> "L" + interfaceOwner(contract) + ";";
            case PromiseType _ ->
                "Lcom/github/andreasarvidsson/eld/runtime/EldPromise;";
            case PromiseSourceType _ ->
                "Lcom/github/andreasarvidsson/eld/runtime/PromiseSource;";
            case ConstType constant -> descriptor(constant.type());
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
        if (type instanceof ConstType constant) {
            return genericSignature(constant.type());
        }
        if (type == BuiltinType.VOID) {
            return "Ljava/lang/Void;";
        }
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
        if (type instanceof PromiseType promise) {
            return "Lcom/github/andreasarvidsson/eld/runtime/EldPromise<"
                + genericSignature(promise.valueType()) + ">;";
        }
        if (type instanceof PromiseSourceType source) {
            return "Lcom/github/andreasarvidsson/eld/runtime/PromiseSource<"
                + genericSignature(source.valueType()) + ">;";
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
        if (type instanceof ConstType constant) {
            return fieldSignature(constant.type());
        }
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

    private void generateOverrideBridges(
        final ClassBuilder writer,
        final String owner,
        final FunctionDeclaration declaration
    ) {
        final FunctionSymbol function =
            (FunctionSymbol) semanticModel.getSymbol(declaration.name());
        final String implementationDescriptor =
            methodDescriptor(function.type());
        final Set<String> generated = new HashSet<>();
        for (final FunctionType inherited : semanticModel
            .getOverrideBridges(function)) {
            final String bridgeDescriptor = methodDescriptor(inherited);
            if (
                bridgeDescriptor.equals(implementationDescriptor)
                    || !generated.add(bridgeDescriptor)
            ) {
                continue;
            }
            generateMethod(
                writer,
                visibilityAccess(semanticModel.getMemberVisibility(function))
                    | ACC_BRIDGE | ACC_SYNTHETIC,
                methodName(function),
                bridgeDescriptor,
                null,
                method -> {
                    method.aload(0);
                    int slot = 1;
                    for (final Type parameter : function.type()
                        .parameterTypes()) {
                        method.with(
                            localInstruction(loadOpcode(parameter), slot)
                        );
                        slot += slots(parameter);
                    }
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc(owner),
                        methodName(function),
                        MethodTypeDesc.ofDescriptor(implementationDescriptor),
                        false
                    );
                    method.with(
                        simpleInstruction(returnOpcode(inherited.returnType()))
                    );
                }
            );
        }
    }

    private void generateInterfaceBridges(
        final ClassBuilder writer,
        final String owner,
        final ClassType classType
    ) {
        final Set<String> generated = new HashSet<>();
        for (final SemanticModel.InterfaceBridge bridge : semanticModel
            .getInterfaceBridges(classType)) {
            final FunctionSymbol implementation = bridge.implementation();
            final FunctionType contract = bridge.contract();
            final String implementationDescriptor =
                methodDescriptor(implementation.type());
            final String bridgeDescriptor = methodDescriptor(contract);
            final String key = methodName(implementation) + bridgeDescriptor;
            if (
                bridgeDescriptor.equals(implementationDescriptor)
                    || !generated.add(key)
            ) {
                continue;
            }
            generateMethod(
                writer,
                visibilityAccess(
                    semanticModel.getMemberVisibility(implementation)
                ) | ACC_BRIDGE | ACC_SYNTHETIC,
                methodName(implementation),
                bridgeDescriptor,
                null,
                method -> {
                    method.aload(0);
                    int slot = 1;
                    for (final Type parameter : implementation.type()
                        .parameterTypes()) {
                        method.with(
                            localInstruction(loadOpcode(parameter), slot)
                        );
                        slot += slots(parameter);
                    }
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc(owner),
                        methodName(implementation),
                        MethodTypeDesc.ofDescriptor(implementationDescriptor),
                        false
                    );
                    method.with(
                        simpleInstruction(returnOpcode(contract.returnType()))
                    );
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
        if (!(ConstType.unwrap(type) instanceof BuiltinType builtin)) {
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
        final Type unqualified = ConstType.unwrap(type);
        if (
            unqualified instanceof ArrayType || unqualified instanceof TupleType
                || unqualified instanceof FunctionType
                || unqualified instanceof BuiltinFunctionType
                || unqualified instanceof ClassType
                || unqualified instanceof InterfaceType
                || unqualified instanceof PromiseType
                || unqualified instanceof PromiseSourceType
        ) {
            return "Ljava/lang/Object;";
        }
        if (type instanceof UnionType) {
            final String representation = descriptor(type);
            return representation.equals("Ljava/lang/String;")
                ? representation
                : "Ljava/lang/Object;";
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
        final Type unqualified = ConstType.unwrap(type);
        return unqualified instanceof UnionType
            || unqualified instanceof BuiltinFunctionType
            || unqualified instanceof ClassType
            || unqualified instanceof InterfaceType
            || unqualified instanceof ArrayType
            || unqualified instanceof TupleType
            || unqualified instanceof FunctionType
            || unqualified instanceof PromiseType
            || unqualified instanceof PromiseSourceType
            || unqualified == BuiltinType.STRING
            || unqualified == BuiltinType.NULL
            || unqualified == BuiltinType.ANY;
    }

    private static ArrayType arrayType(final Type type) {
        return (ArrayType) ConstType.unwrap(type);
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
        private int tryDepth;
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

        private record YieldTarget(
            Label label, boolean discarded, int tryDepth
        ) {
        }

        private final Deque<YieldTarget> yieldTargets = new ArrayDeque<>();

        private record ProtectedRange(Label start, Label end) {
        }

        private final class ProtectedRegion {
            private final List<ProtectedRange> ranges = new ArrayList<>();
            private @Nullable Label start;

            void open() {
                start = method.newLabel();
                method.labelBinding(start);
                // Empty try blocks still need a nonempty protected JVM range.
                method.nop();
            }

            void close() {
                if (start != null) {
                    final Label end = method.newLabel();
                    method.labelBinding(end);
                    ranges.add(new ProtectedRange(start, end));
                    start = null;
                }
            }
        }

        private static final class TryFrame {
            private final @Nullable BlockStatement finallyBody;
            private ProtectedRegion region;
            private final List<Loop> lexicalLoops;
            private final List<YieldTarget> lexicalYields;

            TryFrame(
                final @Nullable BlockStatement finallyBody,
                final ProtectedRegion region,
                final List<Loop> lexicalLoops,
                final List<YieldTarget> lexicalYields
            ) {
                this.finallyBody = finallyBody;
                this.region = region;
                this.lexicalLoops = lexicalLoops;
                this.lexicalYields = lexicalYields;
            }
        }

        private final Deque<TryFrame> tryFrames = new ArrayDeque<>();

        private final Type returnType;
        private int nextLocal;
        private @Nullable String asyncSourceOwner;
        private @Nullable Type asyncResultType;
        private @Nullable AsyncStateMachine asyncStateMachine;
        private @Nullable InstanceContext lexicalInstance;
        private final IdentityHashMap<Expression, Boolean> frameTemporaries =
            new IdentityHashMap<>();
        private final IdentityHashMap<Expression, Boolean> formattedTemporaries =
            new IdentityHashMap<>();
        private final IdentityHashMap<Expression, Integer> formattedLocalTemporaries =
            new IdentityHashMap<>();
        private final IdentityHashMap<Expression, Boolean> preparedCompounds =
            new IdentityHashMap<>();
        private final IdentityHashMap<Expression, Integer> expressionTemporaries =
            new IdentityHashMap<>();
        private boolean beforeBaseInitialization;
        private String lambdaOwner;
        private final IdentityHashMap<Symbol, String> captureFields =
            new IdentityHashMap<>();
        private @Nullable String lexicalReceiverOwner;
        private @Nullable ClassType constructorSuperclass;
        private String constructorSuperclassOwner = "java/lang/Object";
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
            this.lambdaOwner =
                instance == null ? currentOwner : instance.owner();
            this.nextLocal = instance == null ? 0 : 1;
        }

        private int local(final Symbol symbol) {
            return locals.computeIfAbsent(symbol, ignored -> {
                final int slot = nextLocal;
                nextLocal += cell(symbol) ? 1 : slots(symbol.type());
                return slot;
            });
        }

        private void reserveLocals(final int count) {
            nextLocal = Math.max(nextLocal, count);
        }

        private int reserve(final Type type) {
            final int slot = nextLocal;
            nextLocal += slots(type);
            return slot;
        }

        private void asyncCompletionField(
            final String owner,
            final Type resultType
        ) {
            asyncSourceOwner = owner;
            asyncResultType = resultType;
        }

        private void asyncStateMachine(
            final String owner,
            final String promiseOwner,
            final String resumeName,
            final String resumeDescriptor,
            final BlockStatement body,
            final List<AwaitExpression> awaits,
            final IdentityHashMap<AwaitExpression, Label> labels,
            final IdentityHashMap<Expression, Integer> spills,
            final IdentityHashMap<ForEachStatement, Integer> loopIndexes
        ) {
            final IdentityHashMap<AwaitExpression, Integer> states =
                new IdentityHashMap<>();
            final IdentityHashMap<AwaitExpression, Boolean> discarded =
                new IdentityHashMap<>();
            for (int i = 0; i < awaits.size(); i++) {
                final AwaitExpression awaited = awaits.get(i);
                states.put(awaited, i + 1);
                if (discardedAwait(body, awaited)) {
                    discarded.put(awaited, true);
                }
            }
            asyncStateMachine =
                new AsyncStateMachine(
                    owner,
                    promiseOwner,
                    resumeName,
                    resumeDescriptor,
                    states,
                    labels,
                    discarded,
                    spills,
                    loopIndexes
                );
        }

        private void lexicalInstance(final InstanceContext lexical) {
            lexicalInstance = lexical;
            lexicalReceiverOwner = lexical.owner();
        }

        private boolean containsAwait(final AstNode node) {
            return asyncStateMachine != null && AstTraversal
                .anyMatch(node, AwaitExpression.class::isInstance);
        }

        private void saveFrameTemporary(final Expression expression) {
            saveFrameTemporary(expression, () -> expression(expression));
        }

        private void saveLocalTemporary(final Expression expression) {
            saveLocalTemporary(
                expression,
                semanticModel.getEffectiveType(expression),
                () -> expression(expression)
            );
        }

        private void saveLocalTemporary(
            final Expression expression,
            final Type storageType,
            final Runnable emitValue
        ) {
            emitValue.run();
            final int value = reserve(storageType);
            method.with(localInstruction(storeOpcode(storageType), value));
            expressionTemporaries.put(expression, value);
        }

        private void saveTemporary(
            final Expression expression,
            final boolean persistent
        ) {
            if (persistent) {
                saveFrameTemporary(expression);
            }
            else {
                saveLocalTemporary(expression);
            }
        }

        private void saveFrameTemporary(
            final Expression expression,
            final Runnable emitValue
        ) {
            saveFrameTemporary(
                expression,
                semanticModel.getEffectiveType(expression),
                emitValue
            );
        }

        private void saveFrameTemporary(
            final Expression expression,
            final Type storageType,
            final Runnable emitValue
        ) {
            emitValue.run();
            final int value = reserve(storageType);
            method.with(localInstruction(storeOpcode(storageType), value));
            method.aload(0);
            method.fieldAccess(
                GETFIELD,
                classDesc(Objects.requireNonNull(asyncStateMachine).owner()),
                "$spills",
                ClassDesc.ofDescriptor("[Ljava/lang/Object;")
            );
            method.ldc(
                Objects
                    .requireNonNull(asyncStateMachine.spills().get(expression))
            );
            method.with(localInstruction(loadOpcode(storageType), value));
            box(storageType);
            method.aastore();
            frameTemporaries.put(expression, true);
        }

        private void saveFormattedTemporary(
            final Expression expression,
            final boolean persistent
        ) {
            final String argument =
                printArgumentDescriptor(
                    semanticModel.getEffectiveType(expression)
                );
            final Runnable emitValue = () -> {
                expression(expression);
                if (!argument.equals("Ljava/lang/String;")) {
                    method.invoke(
                        INVOKESTATIC,
                        classDesc("java/lang/String"),
                        "valueOf",
                        MethodTypeDesc.ofDescriptor(
                            "(" + argument + ")Ljava/lang/String;"
                        ),
                        false
                    );
                }
            };
            if (persistent) {
                saveFrameTemporary(expression, BuiltinType.STRING, emitValue);
            }
            else {
                saveLocalTemporary(expression, BuiltinType.STRING, emitValue);
                formattedLocalTemporaries.put(
                    expression,
                    Objects.requireNonNull(
                        expressionTemporaries.remove(expression)
                    )
                );
            }
            formattedTemporaries.put(expression, true);
        }

        private void saveArraySpreadTemporary(
            final Expression expression,
            final boolean persistent
        ) {
            final Runnable emitValue = () -> {
                expression(expression);
                final ArrayType array =
                    arrayType(semanticModel.getExpressionType(expression));
                arrayCall(array.elementType(), RuntimeAbi.ArrayMethod.COPY);
            };
            if (persistent) {
                saveFrameTemporary(expression, emitValue);
            }
            else {
                saveLocalTemporary(
                    expression,
                    semanticModel.getEffectiveType(expression),
                    emitValue
                );
            }
        }

        private void saveMapSpreadTemporary(
            final Expression expression,
            final boolean persistent
        ) {
            final Runnable emitValue = () -> {
                method.new_(classDesc("java/util/LinkedHashMap"));
                method.dup();
                expression(expression);
                method.invoke(
                    INVOKESPECIAL,
                    classDesc("java/util/LinkedHashMap"),
                    "<init>",
                    MethodTypeDesc.ofDescriptor("(Ljava/util/Map;)V"),
                    false
                );
            };
            if (persistent) {
                saveFrameTemporary(expression, emitValue);
            }
            else {
                saveLocalTemporary(
                    expression,
                    semanticModel.getEffectiveType(expression),
                    emitValue
                );
            }
        }

        private void loadFrameTemporary(final Expression expression) {
            loadFrameTemporary(
                expression,
                semanticModel.getEffectiveType(expression)
            );
        }

        private void loadFrameTemporary(
            final Expression expression,
            final Type storageType
        ) {
            method.aload(0);
            method.fieldAccess(
                GETFIELD,
                classDesc(Objects.requireNonNull(asyncStateMachine).owner()),
                "$spills",
                ClassDesc.ofDescriptor("[Ljava/lang/Object;")
            );
            method.ldc(
                Objects
                    .requireNonNull(asyncStateMachine.spills().get(expression))
            );
            method.aaload();
            readObject(storageType);
        }

        private List<Expression> prepareCompoundExpression(
            final Expression expression
        ) {
            if (
                !containsAwait(expression)
                    || preparedCompounds.containsKey(expression)
                    || expression instanceof CallExpression
                    || expression instanceof MapExpression
            ) {
                return List.of();
            }
            final List<Expression> operands = asyncCompoundOperands(expression);
            if (operands.isEmpty()) {
                return List.of();
            }
            final IdentityHashMap<Expression, Boolean> arraySpreads =
                new IdentityHashMap<>();
            final IdentityHashMap<Expression, Boolean> mapSpreads =
                new IdentityHashMap<>();
            if (expression instanceof ArrayExpression array) {
                for (final Expression element : array.elements()) {
                    if (element instanceof ArraySpread spread) {
                        arraySpreads.put(spread.expression(), true);
                    }
                }
            }
            else if (expression instanceof MapExpression map) {
                for (final MapElement element : map.elements()) {
                    if (element instanceof MapSpread spread) {
                        mapSpreads.put(spread.expression(), true);
                    }
                }
            }
            preparedCompounds.put(expression, true);
            for (int i = 0; i < operands.size(); i++) {
                final Expression operand = operands.get(i);
                final boolean persistent = hasLaterAwait(operands, i);
                if (expression instanceof FormatStringExpression) {
                    saveFormattedTemporary(operand, persistent);
                }
                else if (arraySpreads.containsKey(operand)) {
                    saveArraySpreadTemporary(operand, persistent);
                }
                else if (mapSpreads.containsKey(operand)) {
                    saveMapSpreadTemporary(operand, persistent);
                }
                else {
                    saveTemporary(operand, persistent);
                }
            }
            return operands;
        }

        private void loadAsyncSource() {
            if (asyncSourceOwner != null) {
                method.aload(0);
                method.fieldAccess(
                    GETFIELD,
                    classDesc(asyncSourceOwner),
                    "$source",
                    ClassDesc.ofDescriptor(
                        "Lcom/github/andreasarvidsson/eld/runtime/PromiseSource;"
                    )
                );
                return;
            }
            throw new IllegalStateException("No async completion source");
        }

        private void resolveAsyncVoid() {
            loadAsyncSource();
            method.invoke(
                INVOKEVIRTUAL,
                classDesc(
                    "com/github/andreasarvidsson/eld/runtime/PromiseSource"
                ),
                "resolve",
                MethodTypeDesc.ofDescriptor("()V"),
                false
            );
            method.return_();
        }

        private void finish(final boolean reachable) {
            if (asyncSourceOwner != null) {
                if (reachable && asyncResultType == BuiltinType.VOID) {
                    resolveAsyncVoid();
                }
                else if (reachable) {
                    method.new_(classDesc("java/lang/IllegalStateException"));
                    method.dup();
                    method.ldc(
                        "Async function completed without returning a value"
                    );
                    method.invoke(
                        INVOKESPECIAL,
                        classDesc("java/lang/IllegalStateException"),
                        "<init>",
                        MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"),
                        false
                    );
                    method.athrow();
                }
                return;
            }
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
                omissionMask(assigned, type);
            }
            method.invoke(
                INVOKESPECIAL,
                classDesc(constructorSuperclassOwner),
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
                case TypeAliasDeclaration _ -> {
                    // Type aliases have no runtime representation.
                }
                case StaticInitializerDeclaration _ -> throw unsupported(
                    item,
                    "Static initializer outside class initialization"
                );
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
                    final Expression initializer = variable.initializer();
                    if (
                        asyncStateMachine != null && instance != null
                            && instance.members().containsKey(symbol)
                            && AstTraversal.anyMatch(
                                initializer,
                                AwaitExpression.class::isInstance
                            )
                    ) {
                        expression(initializer);
                        final int value = reserve(symbol.type());
                        method.with(
                            localInstruction(storeOpcode(symbol.type()), value)
                        );
                        prepareStore(symbol);
                        method.with(
                            localInstruction(loadOpcode(symbol.type()), value)
                        );
                    }
                    else {
                        prepareStore(symbol);
                        expression(initializer);
                    }
                    store(symbol);
                }
                case DestructuringDeclaration declaration -> destructure(
                    declaration.pattern(),
                    declaration.initializer(),
                    true
                );
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
                case DestructuringAssignmentStatement assignment -> destructure(
                    assignment.pattern(),
                    assignment.value(),
                    false
                );
                case IgnoreStatement statement ->
                    discard(statement.expression());
                case ThrowStatement statement -> {
                    expression(statement.value());
                    if (
                        semanticModel.getExpressionType(
                            statement.value()
                        ) instanceof UnionType
                    ) {
                        method.checkcast(classDesc("java/lang/Throwable"));
                    }
                    method.athrow();
                    return false;
                }
                case TryStatement statement -> {
                    return tryStatement(statement);
                }
                case YieldStatement statement -> {
                    final YieldTarget target = yieldTargets.element();
                    if (target.discarded()) {
                        discard(statement.value());
                        abrupt(
                            target.tryDepth(),
                            () -> method.branch(GOTO, target.label())
                        );
                    }
                    else {
                        final Type type =
                            semanticModel.getEffectiveType(statement.value());
                        expression(statement.value());
                        if (tryFrames.size() == target.tryDepth()) {
                            method.branch(GOTO, target.label());
                        }
                        else {
                            final int saved = nextLocal;
                            nextLocal += slots(type);
                            method.with(
                                localInstruction(storeOpcode(type), saved)
                            );
                            abrupt(target.tryDepth(), () -> {
                                method.with(
                                    localInstruction(loadOpcode(type), saved)
                                );
                                method.branch(GOTO, target.label());
                            });
                        }
                    }
                    return false;
                }
                case ReturnStatement statement -> {
                    final Expression value = statement.value();
                    if (asyncSourceOwner != null) {
                        if (value == null) {
                            abrupt(0, this::resolveAsyncVoid);
                        }
                        else {
                            final Type resultType =
                                Objects.requireNonNull(asyncResultType);
                            expression(value);
                            final int saved = reserve(resultType);
                            method.with(
                                localInstruction(storeOpcode(resultType), saved)
                            );
                            abrupt(0, () -> {
                                loadAsyncSource();
                                method.with(
                                    localInstruction(
                                        loadOpcode(resultType),
                                        saved
                                    )
                                );
                                box(resultType);
                                method.invoke(
                                    INVOKEVIRTUAL,
                                    classDesc(
                                        "com/github/andreasarvidsson/eld/runtime/PromiseSource"
                                    ),
                                    "resolve",
                                    MethodTypeDesc
                                        .ofDescriptor("(Ljava/lang/Object;)V"),
                                    false
                                );
                                method.return_();
                            });
                        }
                        return false;
                    }
                    if (value != null) {
                        expression(value);
                    }
                    if (tryFrames.isEmpty()) {
                        method
                            .with(simpleInstruction(returnOpcode(returnType)));
                    }
                    else if (value == null) {
                        abrupt(0, () -> method.return_());
                    }
                    else {
                        final int saved = nextLocal;
                        nextLocal += slots(returnType);
                        method.with(
                            localInstruction(storeOpcode(returnType), saved)
                        );
                        abrupt(0, () -> {
                            method.with(
                                localInstruction(loadOpcode(returnType), saved)
                            );
                            method.with(
                                simpleInstruction(returnOpcode(returnType))
                            );
                        });
                    }
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
                    final Loop loop = loops.element();
                    abrupt(
                        loop.tryDepth,
                        () -> method.branch(GOTO, loop.breakTarget)
                    );
                    return false;
                }
                case ContinueStatement statement -> {
                    if (loops.isEmpty()) {
                        throw unsupported(statement, "Continue outside a loop");
                    }
                    loops.element().hasContinue = true;
                    final Loop loop = loops.element();
                    abrupt(
                        loop.tryDepth,
                        () -> method.branch(GOTO, loop.continueTarget)
                    );
                    return false;
                }
                default -> throw unsupported(item, "Unsupported declaration");
            }
            return true;
        }

        private void abrupt(final int retainedDepth, final Runnable exit) {
            final List<TryFrame> exited = new ArrayList<>();
            boolean reachable = true;
            while (tryFrames.size() > retainedDepth) {
                final TryFrame frame = tryFrames.pop();
                frame.region.close();
                exited.add(frame);
                if (frame.finallyBody != null && !finallyBlock(frame)) {
                    reachable = false;
                    break;
                }
            }
            if (reachable) {
                exit.run();
            }
            for (int i = exited.size() - 1; i >= 0; i--) {
                final TryFrame frame = exited.get(i);
                tryFrames.push(frame);
                frame.region.open();
            }
        }

        private boolean tryStatement(final TryStatement statement) {
            final Label end = method.newLabel();
            final List<Label> handlers =
                statement.catches()
                    .stream()
                    .map(clause -> method.newLabel())
                    .toList();
            final Label finallyHandler = method.newLabel();
            final ProtectedRegion body = new ProtectedRegion();
            final TryFrame frame =
                new TryFrame(
                    statement.finallyBody(),
                    body,
                    List.copyOf(loops),
                    List.copyOf(yieldTargets)
                );
            tryFrames.push(frame);
            body.open();
            final boolean bodyFallsThrough = block(statement.body());
            body.close();
            tryFrames.pop();
            boolean reachable = finishTryPath(frame, bodyFallsThrough, end);
            final List<ProtectedRegion> catchRegions = new ArrayList<>();
            for (int i = 0; i < statement.catches().size(); i++) {
                final CatchClause clause = statement.catches().get(i);
                method.labelBinding(handlers.get(i));
                final Symbol symbol = semanticModel.getSymbol(clause.name());
                if (
                    instance != null && instance.members().containsKey(symbol)
                ) {
                    final int thrown = reserve(BuiltinType.ANY);
                    method.astore(thrown);
                    prepareStore(symbol);
                    method.aload(thrown);
                    store(symbol);
                }
                else {
                    method.astore(local(symbol));
                }
                final ProtectedRegion region = new ProtectedRegion();
                catchRegions.add(region);
                frame.region = region;
                tryFrames.push(frame);
                region.open();
                final boolean catchFallsThrough = block(clause.body());
                region.close();
                tryFrames.pop();
                reachable |= finishTryPath(frame, catchFallsThrough, end);
            }
            if (statement.finallyBody() != null) {
                method.labelBinding(finallyHandler);
                final int thrown = nextLocal++;
                method.astore(thrown);
                if (finallyBlock(frame)) {
                    method.aload(thrown);
                    method.athrow();
                }
            }
            for (int i = 0; i < handlers.size(); i++) {
                final ClassDesc type =
                    classDesc(
                        typeOwner(
                            semanticModel.getResolvedType(
                                statement.catches().get(i).type()
                            )
                        )
                    );
                for (final ProtectedRange range : body.ranges) {
                    method.exceptionCatch(
                        range.start(),
                        range.end(),
                        handlers.get(i),
                        type
                    );
                }
            }
            if (statement.finallyBody() != null) {
                for (final ProtectedRange range : body.ranges) {
                    method.exceptionCatchAll(
                        range.start(),
                        range.end(),
                        finallyHandler
                    );
                }
                for (final ProtectedRegion region : catchRegions) {
                    for (final ProtectedRange range : region.ranges) {
                        method.exceptionCatchAll(
                            range.start(),
                            range.end(),
                            finallyHandler
                        );
                    }
                }
            }
            method.labelBinding(end);
            return reachable;
        }

        private boolean finallyBlock(final TryFrame frame) {
            final List<Loop> previousLoops = List.copyOf(loops);
            final List<YieldTarget> previousYields = List.copyOf(yieldTargets);
            loops.clear();
            loops.addAll(frame.lexicalLoops);
            yieldTargets.clear();
            yieldTargets.addAll(frame.lexicalYields);
            try {
                return block(Objects.requireNonNull(frame.finallyBody));
            }
            finally {
                loops.clear();
                loops.addAll(previousLoops);
                yieldTargets.clear();
                yieldTargets.addAll(previousYields);
            }
        }

        private boolean finishTryPath(
            final TryFrame frame,
            final boolean reachable,
            final Label end
        ) {
            if (!reachable) {
                return false;
            }
            if (frame.finallyBody != null && !finallyBlock(frame)) {
                return false;
            }
            method.branch(GOTO, end);
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
            yieldTargets
                .push(new YieldTarget(end, discarded, tryFrames.size()));
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
                        if (semanticModel.isSwitchDualMatch(match)) {
                            final int matchLocal = nextLocal++;
                            method.with(localInstruction(ASTORE, matchLocal));
                            method.with(localInstruction(ALOAD, matchLocal));
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
                            method.with(
                                localInstruction(
                                    loadOpcode(subjectType),
                                    subject
                                )
                            );
                            method.with(localInstruction(ALOAD, matchLocal));
                            method.with(simpleInstruction(SWAP));
                            method.invoke(
                                INVOKEVIRTUAL,
                                classDesc("java/lang/Class"),
                                "isInstance",
                                MethodTypeDesc
                                    .ofDescriptor("(Ljava/lang/Object;)Z"),
                                false
                            );
                            method.branch(IFNE, body);
                            continue;
                        }
                        if (semanticModel.isSwitchTypeMatch(match)) {
                            method.with(simpleInstruction(SWAP));
                            method.invoke(
                                INVOKEVIRTUAL,
                                classDesc("java/lang/Class"),
                                "isInstance",
                                MethodTypeDesc
                                    .ofDescriptor("(Ljava/lang/Object;)Z"),
                                false
                            );
                            method.branch(IFNE, body);
                        }
                        else if (reference(subjectType)) {
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
            yieldTargets
                .push(new YieldTarget(end, discarded, tryFrames.size()));
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
            loop.tryDepth = tryFrames.size();
            loops.push(loop);
            final boolean reachable = block(body);
            loops.pop();
            return reachable;
        }

        private void forEach(final ForEachStatement statement) {
            if (
                asyncStateMachine != null
                    && asyncStateMachine.loopIndexes().containsKey(statement)
            ) {
                final int loopIndex =
                    Objects.requireNonNull(
                        asyncStateMachine.loopIndexes().get(statement)
                    );
                final Symbol value = semanticModel.getSymbol(statement.value());
                saveFrameTemporary(statement.iterable());
                loadLoopIndexes();
                method.ldc(loopIndex);
                method.iconst_0();
                method.iastore();
                final Label start = method.newLabel();
                final Label next = method.newLabel();
                final Label end = method.newLabel();
                method.labelBinding(start);
                loadLoopIndex(loopIndex);
                loadFrameTemporary(statement.iterable());
                arrayCall(value.type(), RuntimeAbi.ArrayMethod.SIZE);
                method.branch(IF_ICMPGE, end);
                prepareStore(value);
                loadFrameTemporary(statement.iterable());
                loadLoopIndex(loopIndex);
                arrayGet(value.type());
                store(value);
                final IdentifierDeclaration indexName = statement.index();
                if (indexName != null) {
                    final Symbol index = semanticModel.getSymbol(indexName);
                    prepareStore(index);
                    loadLoopIndex(loopIndex);
                    store(index);
                }
                final Loop loop = new Loop(next, end);
                if (loopBody(statement.body(), loop) || loop.hasContinue) {
                    method.labelBinding(next);
                    loadLoopIndexes();
                    method.ldc(loopIndex);
                    method.dup2();
                    method.iaload();
                    method.iconst_1();
                    method.iadd();
                    method.iastore();
                    method.branch(GOTO, start);
                }
                method.labelBinding(end);
                frameTemporaries.remove(statement.iterable());
                return;
            }
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

        private void loadLoopIndexes() {
            method.aload(0);
            method.fieldAccess(
                GETFIELD,
                classDesc(Objects.requireNonNull(asyncStateMachine).owner()),
                "$loopIndexes",
                ClassDesc.ofDescriptor("[I")
            );
        }

        private void loadLoopIndex(final int index) {
            loadLoopIndexes();
            method.ldc(index);
            method.iaload();
        }

        private void load(final Symbol symbol) {
            if (symbol instanceof ClassDeclarationSymbol declaration) {
                method.ldc(classDesc(classOwner(declaration.type())));
            }
            else if (symbol instanceof InterfaceSymbol declaration) {
                method.ldc(classDesc(interfaceOwner(declaration.type())));
            }
            else if (symbol instanceof JavaClassSymbol declaration) {
                method.ldc(classDesc(interfaceOwner(declaration.type())));
            }
            else if (BuiltinFunctionSymbol.PRINT.equals(symbol)) {
                method.fieldAccess(
                    GETSTATIC,
                    classDesc("java/lang/System"),
                    "out",
                    ClassDesc.ofDescriptor("Ljava/io/PrintStream;")
                );
            }
            else if (
                BuiltinFunctionSymbol.DIR.equals(symbol)
                    || BuiltinFunctionSymbol.HELP.equals(symbol)
            ) {
                final boolean dir = BuiltinFunctionSymbol.DIR.equals(symbol);
                method.ldc(
                    MethodHandleDesc.ofMethod(
                        DirectMethodHandleDesc.Kind.STATIC,
                        classDesc(
                            "com/github/andreasarvidsson/eld/runtime/Introspection"
                        ),
                        dir ? "dir" : "help",
                        MethodTypeDesc.ofDescriptor(
                            dir
                                ? "(Ljava/lang/Object;)Lcom/github/andreasarvidsson/eld/runtime/EldObjectArray;"
                                : "(Ljava/lang/Object;)V"
                        )
                    )
                );
            }
            else if (symbol instanceof FunctionSymbol function) {
                final boolean instanceMethod =
                    (instance != null && instance.members().containsKey(symbol))
                        || (lexicalInstance != null
                            && lexicalInstance.members().containsKey(symbol));
                final String methodOwner =
                    semanticModel.isStaticMember(symbol)
                        ? classOwner(semanticModel.getClassMemberOwner(symbol))
                        : !instanceMethod
                            ? moduleName
                            : lexicalInstance != null
                                && lexicalInstance.members().containsKey(symbol)
                                    ? lexicalInstance.owner()
                                    : Objects.requireNonNull(instance).owner();
                method.ldc(
                    MethodHandleDesc.ofMethod(
                        instanceMethod
                            ? DirectMethodHandleDesc.Kind.VIRTUAL
                            : DirectMethodHandleDesc.Kind.STATIC,
                        classDesc(methodOwner),
                        methodName(function),
                        MethodTypeDesc
                            .ofDescriptor(methodDescriptor(function.type()))
                    )
                );
                if (instanceMethod) {
                    if (
                        lexicalInstance != null
                            && lexicalInstance.members().containsKey(symbol)
                    ) {
                        loadLexicalReceiver();
                    }
                    else {
                        method.aload(0);
                    }
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
            else if (semanticModel.isStaticMember(symbol)) {
                method.fieldAccess(
                    GETSTATIC,
                    classDesc(
                        classOwner(semanticModel.getClassMemberOwner(symbol))
                    ),
                    symbol.name(),
                    ClassDesc.ofDescriptor(descriptor(symbol.type()))
                );
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
            else if (
                lexicalInstance != null
                    && lexicalInstance.members().containsKey(symbol)
            ) {
                loadLexicalReceiver();
                method.fieldAccess(
                    GETFIELD,
                    classDesc(lexicalInstance.owner()),
                    Objects
                        .requireNonNull(lexicalInstance.members().get(symbol)),
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
            if (semanticModel.isStaticMember(symbol)) {
                return false;
            }
            if (instance != null && instance.members().containsKey(symbol)) {
                method.aload(0);
                return true;
            }
            if (
                lexicalInstance != null
                    && lexicalInstance.members().containsKey(symbol)
            ) {
                loadLexicalReceiver();
                return true;
            }
            return false;
        }

        private void loadLexicalReceiver() {
            method.aload(0);
            method.fieldAccess(
                GETFIELD,
                classDesc(Objects.requireNonNull(instance).owner()),
                "$receiver",
                ClassDesc.ofDescriptor(
                    "L" + Objects.requireNonNull(lexicalInstance).owner() + ";"
                )
            );
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
            if (semanticModel.isStaticMember(symbol)) {
                method.fieldAccess(
                    PUTSTATIC,
                    classDesc(
                        classOwner(semanticModel.getClassMemberOwner(symbol))
                    ),
                    symbol.name(),
                    ClassDesc.ofDescriptor(descriptor(symbol.type()))
                );
            }
            else if (
                instance != null && instance.members().containsKey(symbol)
            ) {
                method.fieldAccess(
                    PUTFIELD,
                    classDesc(instance.owner()),
                    Objects.requireNonNull(instance.members().get(symbol)),
                    ClassDesc.ofDescriptor(descriptor(symbol.type()))
                );
            }
            else if (
                lexicalInstance != null
                    && lexicalInstance.members().containsKey(symbol)
            ) {
                method.fieldAccess(
                    PUTFIELD,
                    classDesc(lexicalInstance.owner()),
                    Objects
                        .requireNonNull(lexicalInstance.members().get(symbol)),
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
                expression instanceof AwaitExpression awaited
                    && asyncStateMachine != null
                    && asyncStateMachine.discarded().containsKey(awaited)
            ) {
                return;
            }
            final Integer temporary = expressionTemporaries.get(expression);
            if (temporary != null && temporary < 0) {
                return;
            }
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
            if (frameTemporaries.containsKey(expression)) {
                loadFrameTemporary(expression);
                return;
            }
            final Integer savedTemporary =
                expressionTemporaries.get(expression);
            if (savedTemporary != null) {
                if (savedTemporary >= 0) {
                    method.with(
                        localInstruction(
                            loadOpcode(
                                semanticModel.getEffectiveType(expression)
                            ),
                            savedTemporary
                        )
                    );
                }
                return;
            }
            final List<Expression> preparedOperands =
                prepareCompoundExpression(expression);
            try {
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
                            semanticModel.getExpressionType(
                                expression
                            ) == BuiltinType.I64
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
                        (ConstantDesc) Objects
                            .requireNonNull(constantValue(unary))
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
                            final boolean formatted =
                                formattedTemporaries.containsKey(part);
                            if (formatted) {
                                final Integer local =
                                    formattedLocalTemporaries.get(part);
                                if (local != null) {
                                    method.aload(local);
                                }
                                else {
                                    loadFrameTemporary(
                                        part,
                                        BuiltinType.STRING
                                    );
                                }
                            }
                            else {
                                expression(part);
                            }
                            final String argument =
                                formatted
                                    ? "Ljava/lang/String;"
                                    : printArgumentDescriptor(
                                        semanticModel.getEffectiveType(part)
                                    );
                            method.invoke(
                                INVOKEVIRTUAL,
                                classDesc("java/lang/StringBuilder"),
                                "append",
                                MethodTypeDesc.ofDescriptor(
                                    "(" + argument
                                        + ")Ljava/lang/StringBuilder;"
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
                    case IdentifierExpression identifier -> {
                        final Symbol symbol =
                            semanticModel.getReference(identifier);
                        load(symbol);
                        final Type narrowed =
                            semanticModel.findNarrowedType(identifier);
                        if (narrowed != null && reference(symbol.type())) {
                            readObject(narrowed);
                        }
                    }
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
                                method.with(
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
                        yieldTargets.push(
                            new YieldTarget(end, false, tryFrames.size())
                        );
                        conditional(conditional, end);
                        yieldTargets.pop();
                    }
                    case SwitchExpression selection -> selection(selection);
                    case AssignmentExpression assignment -> assign(assignment);
                    case CallExpression call -> call(call);
                    case AwaitExpression awaited -> {
                        if (asyncStateMachine != null) {
                            final AsyncStateMachine stateMachine =
                                asyncStateMachine;
                            expression(awaited.expression());
                            method.aload(0);
                            method.ldc(
                                Objects.requireNonNull(
                                    stateMachine.states().get(awaited)
                                )
                            );
                            method.fieldAccess(
                                PUTFIELD,
                                classDesc(stateMachine.owner()),
                                "$state",
                                ClassDesc.ofDescriptor("I")
                            );
                            method.ldc(
                                MethodHandleDesc.ofMethod(
                                    DirectMethodHandleDesc.Kind.STATIC,
                                    classDesc(stateMachine.owner()),
                                    stateMachine.resumeName(),
                                    MethodTypeDesc.ofDescriptor(
                                        stateMachine.resumeDescriptor()
                                    )
                                )
                            );
                            method.aload(0);
                            method.invoke(
                                INVOKEVIRTUAL,
                                classDesc(stateMachine.promiseOwner()),
                                "then",
                                MethodTypeDesc.ofDescriptor(
                                    "(Ljava/lang/invoke/MethodHandle;Ljava/lang/Object;)V"
                                ),
                                false
                            );
                            method.return_();
                            method.labelBinding(
                                Objects.requireNonNull(
                                    stateMachine.labels().get(awaited)
                                )
                            );
                            final Label value = method.newLabel();
                            method.aload(2);
                            method.branch(IFNULL, value);
                            method.aload(2);
                            method.athrow();
                            method.labelBinding(value);
                            final Type valueType =
                                ((PromiseType) semanticModel
                                    .getExpressionType(awaited.expression()))
                                    .valueType();
                            if (
                                valueType != BuiltinType.VOID
                                    && !stateMachine.discarded()
                                        .containsKey(awaited)
                            ) {
                                method.aload(1);
                                readObject(valueType);
                            }
                            break;
                        }
                        expression(awaited.expression());
                        method.invoke(
                            INVOKEVIRTUAL,
                            classDesc(
                                "com/github/andreasarvidsson/eld/runtime/EldPromise"
                            ),
                            "awaitNow",
                            MethodTypeDesc.ofDescriptor("()Ljava/lang/Object;"),
                            false
                        );
                        final Type valueType =
                            ((PromiseType) semanticModel
                                .getExpressionType(awaited.expression()))
                                .valueType();
                        if (valueType == BuiltinType.VOID) {
                            method.pop();
                        }
                        else {
                            readObject(valueType);
                        }
                    }
                    case MemberExpression member -> member(member);
                    case ThisExpression _ -> {
                        method.aload(0);
                        if (lexicalReceiverOwner != null) {
                            method.fieldAccess(
                                GETFIELD,
                                classDesc(
                                    Objects.requireNonNull(instance).owner()
                                ),
                                "$receiver",
                                ClassDesc.ofDescriptor(
                                    "L" + lexicalReceiverOwner + ";"
                                )
                            );
                        }
                    }
                    case NewExpression creation -> {
                        if (
                            semanticModel.getExpressionType(
                                creation
                            ) instanceof PromiseSourceType
                        ) {
                            final String owner =
                                "com/github/andreasarvidsson/eld/runtime/PromiseSource";
                            method.new_(classDesc(owner));
                            method.dup();
                            method.invoke(
                                INVOKESPECIAL,
                                classDesc(owner),
                                "<init>",
                                MethodTypeDesc.ofDescriptor("()V"),
                                false
                            );
                            break;
                        }
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
                            for (int i = 0; i < creation.arguments()
                                .size(); i++) {
                                final Expression argument =
                                    creation.arguments().get(i);
                                expression(argument);
                                if (
                                    !constructor.getParameterTypes()[i]
                                        .isPrimitive()
                                ) {
                                    box(
                                        semanticModel.getEffectiveType(argument)
                                    );
                                }
                            }
                            method
                                .invoke(
                                    INVOKESPECIAL,
                                    classDesc(owner),
                                    "<init>",
                                    MethodTypeDesc.ofDescriptor(
                                        MethodTypeDesc
                                            .of(
                                                ClassDesc.ofDescriptor("V"),
                                                Arrays
                                                    .stream(
                                                        constructor
                                                            .getParameterTypes()
                                                    )
                                                    .map(
                                                        type -> type
                                                            .describeConstable()
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
                            ((ClassType) semanticModel
                                .getExpressionType(creation)).name();
                        final String owner =
                            classOwners
                                .getOrDefault(name, moduleName + "$" + name);
                        method.new_(classDesc(owner));
                        method.dup();
                        final FunctionType constructorType =
                            semanticModel.getConstructor(
                                (ClassType) semanticModel
                                    .getExpressionType(creation)
                            );
                        constructorArguments(creation, constructorType);
                        final boolean omitted =
                            creation.arguments().size() < constructorType
                                .parameterTypes()
                                .size();
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
                    case NamedArgumentExpression named -> throw unsupported(
                        named,
                        "Named argument outside a call"
                    );
                    case TupleExpression tuple -> tuple(tuple);
                    case ArraySpread spread -> throw unsupported(
                        spread,
                        "Array spread must be compiled in an array literal"
                    );
                    case ArrayExpression array -> array(array);
                    case MapExpression map -> map(map);
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
            finally {
                for (final Expression operand : preparedOperands) {
                    frameTemporaries.remove(operand);
                    formattedTemporaries.remove(operand);
                    formattedLocalTemporaries.remove(operand);
                    expressionTemporaries.remove(operand);
                }
                if (!preparedOperands.isEmpty()) {
                    preparedCompounds.remove(expression);
                }
            }
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
            final Type unqualified = ConstType.unwrap(type);
            if (unqualified instanceof ArrayType array) {
                method.ldc(
                    RuntimeAbi.array(array.elementType()).elementDescriptor
                );
                method.invoke(
                    INVOKESTATIC,
                    classDesc(
                        "com/github/andreasarvidsson/eld/runtime/EldArray"
                    ),
                    "fromObjectArray",
                    MethodTypeDesc.ofDescriptor(
                        "(Ljava/lang/Object;Ljava/lang/String;)Lcom/github/andreasarvidsson/eld/runtime/EldArray;"
                    ),
                    false
                );
                method.checkcast(
                    classDesc(RuntimeAbi.array(array.elementType()).owner)
                );
                return;
            }
            final String owner = boxedOwner(type);
            if (owner != null) {
                method.checkcast(classDesc(owner));
                final String valueMethod = switch ((BuiltinType) unqualified) {
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
                            field.type(),
                            false
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
                                    if (longOmissionMask(signature)) {
                                        defaults.lload(defaultSlot);
                                    }
                                    else {
                                        defaults.iload(defaultSlot);
                                    }
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

        private void promiseCall(
            final CallExpression call,
            final Method promiseMethod
        ) {
            switch (promiseMethod.getName()) {
                case "resolve" -> {
                    if (call.arguments().isEmpty()) {
                        method.invoke(
                            INVOKESTATIC,
                            classDesc(
                                "com/github/andreasarvidsson/eld/runtime/EldPromise"
                            ),
                            "resolve",
                            MethodTypeDesc.ofDescriptor(
                                "()Lcom/github/andreasarvidsson/eld/runtime/EldPromise;"
                            ),
                            false
                        );
                        return;
                    }
                    final Expression value = call.arguments().getFirst();
                    expression(value);
                    if (
                        semanticModel
                            .getEffectiveType(value) instanceof PromiseType
                    ) {
                        return;
                    }
                    box(semanticModel.getEffectiveType(value));
                    method.invoke(
                        INVOKESTATIC,
                        classDesc(
                            "com/github/andreasarvidsson/eld/runtime/EldPromise"
                        ),
                        "resolve",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/Object;)Lcom/github/andreasarvidsson/eld/runtime/EldPromise;"
                        ),
                        false
                    );
                }
                case "reject" -> {
                    expression(call.arguments().getFirst());
                    method.invoke(
                        INVOKESTATIC,
                        classDesc(
                            "com/github/andreasarvidsson/eld/runtime/EldPromise"
                        ),
                        "reject",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/Throwable;)Lcom/github/andreasarvidsson/eld/runtime/EldPromise;"
                        ),
                        false
                    );
                }
                case "all" -> {
                    expression(call.arguments().getFirst());
                    method.invoke(
                        INVOKESTATIC,
                        classDesc(
                            "com/github/andreasarvidsson/eld/runtime/EldPromise"
                        ),
                        "all",
                        MethodTypeDesc.ofDescriptor(
                            "(Lcom/github/andreasarvidsson/eld/runtime/EldObjectArray;)Lcom/github/andreasarvidsson/eld/runtime/EldPromise;"
                        ),
                        false
                    );
                }
                default -> throw new IllegalStateException(
                    "Unsupported Promise API method: " + promiseMethod
                );
            }
        }

        private void call(final CallExpression call) {
            final Method promiseMethod = semanticModel.getPromiseMethod(call);
            final @Nullable Type preparedCalleeType =
                promiseMethod == null
                    ? semanticModel.getExpressionType(call.callee())
                    : null;
            if (
                containsAwait(call) && !preparedCompounds.containsKey(call)
                    && promiseMethod == null
                    && preparedCalleeType != BuiltinFunctionType.PRINT
            ) {
                preparedCompounds.put(call, true);
                final List<Expression> operands = asyncCallOperands(call);
                for (int i = 0; i < operands.size(); i++) {
                    saveTemporary(operands.get(i), hasLaterAwait(operands, i));
                }
                try {
                    call(call);
                }
                finally {
                    for (final Expression operand : operands) {
                        frameTemporaries.remove(operand);
                        expressionTemporaries.remove(operand);
                    }
                    preparedCompounds.remove(call);
                }
                return;
            }
            if (promiseMethod != null) {
                promiseCall(call, promiseMethod);
                return;
            }
            final Type calleeType = Objects.requireNonNull(preparedCalleeType);
            if (calleeType == BuiltinFunctionType.ARRAY_SORT) {
                final MemberExpression member =
                    (MemberExpression) unwrap(call.callee());
                expression(member.target());
                final ArrayType array =
                    arrayType(semanticModel.getExpressionType(member.target()));
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
                if (
                    asyncStateMachine != null && call.arguments()
                        .stream()
                        .anyMatch(
                            argument -> AstTraversal.anyMatch(
                                argument,
                                AwaitExpression.class::isInstance
                            )
                        )
                ) {
                    final Expression argument = call.arguments().getFirst();
                    final Type argumentType =
                        semanticModel.getEffectiveType(argument);
                    expression(argument);
                    final int value = reserve(argumentType);
                    method.with(
                        localInstruction(storeOpcode(argumentType), value)
                    );
                    expression(call.callee());
                    method.with(
                        localInstruction(loadOpcode(argumentType), value)
                    );
                }
                else {
                    expression(call.callee());
                    for (final Expression argument : call.arguments()) {
                        expression(argument);
                    }
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
            if (
                calleeType == BuiltinFunctionType.DIR
                    || calleeType == BuiltinFunctionType.HELP
            ) {
                final Expression argument = call.arguments().getFirst();
                expression(argument);
                box(semanticModel.getEffectiveType(argument));
                final boolean dir = calleeType == BuiltinFunctionType.DIR;
                method.invoke(
                    INVOKESTATIC,
                    classDesc(
                        "com/github/andreasarvidsson/eld/runtime/Introspection"
                    ),
                    dir ? "dir" : "help",
                    MethodTypeDesc.ofDescriptor(
                        dir
                            ? "(Ljava/lang/Object;)Lcom/github/andreasarvidsson/eld/runtime/EldObjectArray;"
                            : "(Ljava/lang/Object;)V"
                    ),
                    false
                );
                return;
            }
            final FunctionType type = (FunctionType) calleeType;
            final Expression callee = unwrap(call.callee());
            if (
                callee instanceof MemberExpression member
                    && semanticModel
                        .getMemberOwner(member) instanceof PromiseSourceType
                    && semanticModel
                        .getReference(member.member()) instanceof FunctionSymbol
            ) {
                expression(member.target());
                if (!call.arguments().isEmpty()) {
                    final Expression argument = call.arguments().getFirst();
                    expression(argument);
                    if (member.member().name().equals("resolve")) {
                        box(semanticModel.getEffectiveType(argument));
                    }
                }
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(
                        "com/github/andreasarvidsson/eld/runtime/PromiseSource"
                    ),
                    member.member().name(),
                    MethodTypeDesc.ofDescriptor(
                        member.member().name().equals("resolve")
                            ? call.arguments().isEmpty()
                                ? "()V"
                                : "(Ljava/lang/Object;)V"
                            : "(Ljava/lang/Throwable;)V"
                    ),
                    false
                );
                return;
            }
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
                    javaMethodOwner(member, javaMethod),
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
                    semanticModel.isStaticMember(function)
                        ? INVOKESTATIC
                        : semanticModel
                            .getMemberOwner(member) instanceof InterfaceType
                                ? INVOKEINTERFACE
                                : INVOKEVIRTUAL,
                    classDesc(memberOwner(member)),
                    methodName(function),
                    MethodTypeDesc
                        .ofDescriptor(callDescriptor(call, function.type())),
                    !semanticModel.isStaticMember(function) && semanticModel
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
                    (instance != null
                        && instance.members().containsKey(function))
                        || (lexicalInstance != null
                            && lexicalInstance.members().containsKey(function));
                if (instanceMethod) {
                    if (
                        lexicalInstance != null
                            && lexicalInstance.members().containsKey(function)
                    ) {
                        loadLexicalReceiver();
                    }
                    else {
                        method.aload(0);
                    }
                }
                callArguments(call);
                method.invoke(
                    instanceMethod ? INVOKEVIRTUAL : INVOKESTATIC,
                    classDesc(
                        semanticModel.isStaticMember(function)
                            ? classOwner(
                                semanticModel.getClassMemberOwner(function)
                            )
                            : instanceMethod
                                ? lexicalInstance != null
                                    && lexicalInstance.members()
                                        .containsKey(function)
                                            ? lexicalInstance.owner()
                                            : Objects.requireNonNull(instance)
                                                .owner()
                                : moduleName
                    ),
                    methodName(function),
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

        private void omissionMask(
            final boolean[] assigned,
            final FunctionType type
        ) {
            if (longOmissionMask(type)) {
                long mask = 0;
                for (int i = 0; i < assigned.length; i++) {
                    if (!assigned[i]) {
                        mask |= 1L << i;
                    }
                }
                method.ldc(mask);
            }
            else {
                int mask = 0;
                for (int i = 0; i < assigned.length; i++) {
                    if (!assigned[i]) {
                        mask |= 1 << i;
                    }
                }
                method.ldc(mask);
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
                omissionMask(assigned, type);
            }
        }

        private void constructorArguments(
            final NewExpression creation,
            final FunctionType type
        ) {
            if (
                creation.arguments().size() == type.parameterTypes().size()
                    && creation.arguments()
                        .stream()
                        .noneMatch(
                            argument -> argument instanceof NamedArgumentExpression
                        )
            ) {
                for (final Expression argument : creation.arguments()) {
                    expression(argument);
                }
                return;
            }
            final List<Integer> parameters =
                semanticModel.getConstructorArgumentParameters(creation);
            final int[] locals = new int[type.parameterTypes().size()];
            final boolean[] assigned = new boolean[locals.length];
            for (int i = 0; i < parameters.size(); i++) {
                final Expression supplied = creation.arguments().get(i);
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
                omissionMask(assigned, type);
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

        private void destructure(
            final Pattern pattern,
            final Expression source,
            final boolean declaration
        ) {
            expression(source);
            final int sourceLocal = nextLocal++;
            method.astore(sourceLocal);
            final List<PatternValue> values = new ArrayList<>();
            extractPatternValues(pattern, sourceLocal, values);

            if (declaration) {
                for (final PatternValue value : values) {
                    final Symbol symbol =
                        semanticModel.getPatternSymbol(value.binding());
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
                }
            }
            for (final PatternValue value : values) {
                final Symbol symbol =
                    semanticModel.getPatternSymbol(value.binding());
                prepareStore(symbol);
                method.with(
                    localInstruction(loadOpcode(symbol.type()), value.local())
                );
                store(symbol);
            }
        }

        private void extractPatternValues(
            final Pattern pattern,
            final int sourceLocal,
            final List<PatternValue> values
        ) {
            if (pattern instanceof TuplePattern tuplePattern) {
                for (int i = 0; i < tuplePattern.elements().size(); i++) {
                    final Pattern element = tuplePattern.elements().get(i);
                    if (element instanceof DiscardPattern) {
                        continue;
                    }
                    final IdentifierPattern binding =
                        (IdentifierPattern) element;
                    method.aload(sourceLocal);
                    method.ldc(i);
                    method.invoke(
                        INVOKEVIRTUAL,
                        classDesc(
                            "com/github/andreasarvidsson/eld/runtime/EldTuple"
                        ),
                        "get",
                        MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Object;"),
                        false
                    );
                    final Type sourceType =
                        semanticModel.getPatternSourceType(binding);
                    readObject(sourceType);
                    savePatternValue(binding, sourceType, values);
                }
                return;
            }
            final RecordPattern record = (RecordPattern) pattern;
            final ClassType recordType =
                semanticModel.getRecordPatternType(record);
            final String owner =
                internalName(ClassDesc.ofDescriptor(descriptor(recordType)));
            for (final RecordPatternField field : record.fields()) {
                if (field.target() instanceof DiscardPattern) {
                    continue;
                }
                final IdentifierPattern binding =
                    (IdentifierPattern) field.target();
                final Type sourceType =
                    semanticModel.getPatternSourceType(binding);
                method.aload(sourceLocal);
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(owner),
                    field.component().name(),
                    MethodTypeDesc.ofDescriptor("()" + descriptor(sourceType)),
                    false
                );
                savePatternValue(binding, sourceType, values);
            }
        }

        private void savePatternValue(
            final IdentifierPattern binding,
            final Type sourceType,
            final List<PatternValue> values
        ) {
            final Type targetType =
                semanticModel.getPatternSymbol(binding).type();
            convertPatternValue(
                sourceType,
                targetType,
                semanticModel.getPatternConversionType(binding)
            );
            final int valueLocal = reserve(targetType);
            method.with(localInstruction(storeOpcode(targetType), valueLocal));
            values.add(new PatternValue(binding, valueLocal));
        }

        private void convertPatternValue(
            final Type source,
            final Type target,
            final Type conversion
        ) {
            final Type targetValue = ConstType.unwrap(target);
            if (
                targetValue == BuiltinType.ANY
                    || (targetValue instanceof InterfaceType
                        && JavaTypes.boxedClass(source) != null)
            ) {
                box(source);
            }
            else if (targetValue instanceof UnionType) {
                if (!(source instanceof UnionType)) {
                    if (
                        conversion instanceof InterfaceType
                            && JavaTypes.boxedClass(source) != null
                    ) {
                        box(source);
                    }
                    else {
                        convert(source, conversion);
                        box(conversion);
                    }
                }
                final String descriptor = descriptor(target);
                if (
                    !referenceAssignable(
                        boxedDescriptor(conversion),
                        descriptor
                    )
                ) {
                    method.checkcast(
                        classDesc(
                            internalName(ClassDesc.ofDescriptor(descriptor))
                        )
                    );
                }
            }
            else {
                convert(source, target);
            }
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
                arrayType(semanticModel.getExpressionType(array)).elementType();
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
                        arrayType(
                            semanticModel.getExpressionType(spread.expression())
                        ).elementType();
                    expression(spread.expression());
                    // Snapshot only when later expressions might mutate the contributed range.
                    if (
                        !frameTemporaries.containsKey(
                            spread.expression()
                        ) && array.elements()
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
                        arrayType(
                            semanticModel.getExpressionType(spread.expression())
                        ).elementType();
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
                arrayType(semanticModel.getExpressionType(array)).elementType();
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

        private void map(final MapExpression map) {
            if (containsAwait(map)) {
                asyncMap(map);
                return;
            }
            method.new_(classDesc("java/util/LinkedHashMap"));
            method.dup();
            method.invoke(
                INVOKESPECIAL,
                classDesc("java/util/LinkedHashMap"),
                "<init>",
                MethodTypeDesc.ofDescriptor("()V"),
                false
            );
            for (final MapElement element : map.elements()) {
                method.dup();
                if (element instanceof MapEntry entry) {
                    expression(entry.key());
                    box(semanticModel.getEffectiveType(entry.key()));
                    expression(entry.value());
                    box(semanticModel.getEffectiveType(entry.value()));
                    method.invoke(
                        INVOKEINTERFACE,
                        classDesc("java/util/Map"),
                        "put",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                        ),
                        true
                    );
                    method.pop();
                }
                else if (element instanceof MapSpread spread) {
                    expression(spread.expression());
                    method.invoke(
                        INVOKEINTERFACE,
                        classDesc("java/util/Map"),
                        "putAll",
                        MethodTypeDesc.ofDescriptor("(Ljava/util/Map;)V"),
                        true
                    );
                }
            }
        }

        private void asyncMap(final MapExpression map) {
            saveFrameTemporary(map, () -> {
                method.new_(classDesc("java/util/LinkedHashMap"));
                method.dup();
                method.invoke(
                    INVOKESPECIAL,
                    classDesc("java/util/LinkedHashMap"),
                    "<init>",
                    MethodTypeDesc.ofDescriptor("()V"),
                    false
                );
            });
            frameTemporaries.remove(map);
            for (final MapElement element : map.elements()) {
                if (element instanceof MapEntry entry) {
                    final boolean valueAwaits = containsAwait(entry.value());
                    if (containsAwait(entry.key()) || valueAwaits) {
                        saveTemporary(entry.key(), valueAwaits);
                    }
                    if (valueAwaits) {
                        saveLocalTemporary(entry.value());
                    }
                    loadFrameTemporary(map);
                    expression(entry.key());
                    box(semanticModel.getEffectiveType(entry.key()));
                    expression(entry.value());
                    box(semanticModel.getEffectiveType(entry.value()));
                    method.invoke(
                        INVOKEINTERFACE,
                        classDesc("java/util/Map"),
                        "put",
                        MethodTypeDesc.ofDescriptor(
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
                        ),
                        true
                    );
                    method.pop();
                    frameTemporaries.remove(entry.key());
                    expressionTemporaries.remove(entry.key());
                    expressionTemporaries.remove(entry.value());
                }
                else if (element instanceof MapSpread spread) {
                    final Expression source = spread.expression();
                    if (containsAwait(source)) {
                        saveLocalTemporary(source);
                    }
                    loadFrameTemporary(map);
                    expression(source);
                    method.invoke(
                        INVOKEINTERFACE,
                        classDesc("java/util/Map"),
                        "putAll",
                        MethodTypeDesc.ofDescriptor("(Ljava/util/Map;)V"),
                        true
                    );
                    expressionTemporaries.remove(source);
                }
            }
            loadFrameTemporary(map);
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
                arrayType(semanticModel.getExpressionType(slice)).elementType(),
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

        private ClassDesc javaMethodOwner(
            final MemberExpression member,
            final Method method
        ) {
            if (method.getDeclaringClass() != Object.class) {
                return classDesc(
                    method.getDeclaringClass().getName().replace('.', '/')
                );
            }
            final Type owner = semanticModel.getMemberOwner(member);
            return switch (owner) {
                case ClassType _ -> classDesc(memberOwner(member));
                case BuiltinType builtin when builtin == BuiltinType.STRING ->
                    classDesc("java/lang/String");
                case ArrayType array ->
                    classDesc(RuntimeAbi.array(array.elementType()).owner);
                case TupleType _ -> classDesc(
                    "com/github/andreasarvidsson/eld/runtime/EldTuple"
                );
                case FunctionType _ ->
                    classDesc("java/lang/invoke/MethodHandle");
                case BuiltinFunctionType builtin -> classDesc(
                    builtin == BuiltinFunctionType.PRINT
                        ? "java/io/PrintStream"
                        : "java/lang/invoke/MethodHandle"
                );
                case InterfaceType contract when contract.javaClass() != null
                    && !contract.javaClass().isInterface() ->
                    classDesc(contract.javaClass().getName().replace('.', '/'));
                case PromiseType _ -> classDesc(
                    "com/github/andreasarvidsson/eld/runtime/EldPromise"
                );
                case PromiseSourceType _ -> classDesc(
                    "com/github/andreasarvidsson/eld/runtime/PromiseSource"
                );
                default -> classDesc("java/lang/Object");
            };
        }

        private void memberReceiver(final MemberExpression member) {
            if (
                !semanticModel
                    .isStaticMember(semanticModel.getReference(member.member()))
            ) {
                expression(member.target());
            }
        }

        private void member(final MemberExpression member) {
            final Symbol symbol = semanticModel.getReference(member.member());
            if (
                semanticModel
                    .getMemberOwner(member) instanceof PromiseSourceType
                    && member.member().name().equals("promise")
            ) {
                memberReceiver(member);
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(
                        "com/github/andreasarvidsson/eld/runtime/PromiseSource"
                    ),
                    "promise",
                    MethodTypeDesc.ofDescriptor(
                        "()Lcom/github/andreasarvidsson/eld/runtime/EldPromise;"
                    ),
                    false
                );
                return;
            }
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
                        javaMethodOwner(member, javaMethod),
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
                        semanticModel.isStaticMember(function)
                            ? DirectMethodHandleDesc.Kind.STATIC
                            : semanticModel.getMemberOwner(
                                member
                            ) instanceof InterfaceType
                                ? DirectMethodHandleDesc.Kind.INTERFACE_VIRTUAL
                                : DirectMethodHandleDesc.Kind.VIRTUAL,
                        classDesc(memberOwner(member)),
                        methodName(function),
                        MethodTypeDesc
                            .ofDescriptor(methodDescriptor(function.type()))
                    )
                );
                if (!semanticModel.isStaticMember(function)) {
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
            else if (
                semanticModel.getMemberOwner(member) instanceof ClassType type
                    && semanticModel.isRecordClass(type)
            ) {
                memberReceiver(member);
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc(memberOwner(member)),
                    symbol.name(),
                    MethodTypeDesc
                        .ofDescriptor("()" + descriptor(symbol.type())),
                    false
                );
            }
            else {
                memberReceiver(member);
                method.fieldAccess(
                    semanticModel.isStaticMember(symbol) ? GETSTATIC : GETFIELD,
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
                final boolean containsAwait =
                    asyncStateMachine != null && AstTraversal.anyMatch(
                        assignment.value(),
                        AwaitExpression.class::isInstance
                    );
                final boolean instanceField;
                if (containsAwait) {
                    expression(assignment.value());
                    final int value = reserve(symbol.type());
                    method.with(
                        localInstruction(storeOpcode(symbol.type()), value)
                    );
                    instanceField = prepareStore(symbol);
                    method.with(
                        localInstruction(loadOpcode(symbol.type()), value)
                    );
                }
                else {
                    instanceField = prepareStore(symbol);
                    expression(assignment.value());
                }
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
                final boolean staticMember =
                    semanticModel.isStaticMember(
                        semanticModel.getReference(member.member())
                    );
                memberReceiver(member);
                expression(assignment.value());
                method.with(
                    simpleInstruction(
                        slots(type) == 2
                            ? staticMember ? DUP2 : DUP2_X1
                            : staticMember ? DUP : DUP_X1
                    )
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
                    semanticModel.isStaticMember(
                        semanticModel.getReference(member.member())
                    ) ? PUTSTATIC : PUTFIELD,
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
                final boolean boxedStorage =
                    reference(symbol.type()) && !symbol.type().equals(type);
                if (boxedStorage) {
                    readObject(type);
                }
                if (postfix) {
                    method.with(
                        simpleInstruction(
                            slots(type) == 2
                                ? (instanceField ? DUP2_X1 : DUP2)
                                : (instanceField ? DUP_X1 : DUP)
                        )
                    );
                }
                addOne(type, increase);
                if (!postfix) {
                    method.with(
                        simpleInstruction(
                            slots(type) == 2
                                ? (instanceField ? DUP2_X1 : DUP2)
                                : (instanceField ? DUP_X1 : DUP)
                        )
                    );
                }
                if (boxedStorage) {
                    box(type);
                }
                store(symbol);
            }
            else if (target instanceof MemberExpression member) {
                final boolean staticMember =
                    semanticModel.isStaticMember(
                        semanticModel.getReference(member.member())
                    );
                if (staticMember) {
                    method.fieldAccess(
                        GETSTATIC,
                        classDesc(memberOwner(member)),
                        semanticModel.getReference(member.member()).name(),
                        ClassDesc.ofDescriptor(descriptor(type))
                    );
                }
                else {
                    memberReceiver(member);
                    method.dup();
                    if (
                        semanticModel
                            .getMemberOwner(member) instanceof InterfaceType
                    ) {
                        method.invoke(
                            INVOKEINTERFACE,
                            classDesc(memberOwner(member)),
                            "$get$"
                                + semanticModel.getReference(member.member())
                                    .name(),
                            MethodTypeDesc
                                .ofDescriptor("()" + descriptor(type)),
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
                }
                if (postfix) {
                    method.with(
                        simpleInstruction(
                            slots(type) == 2
                                ? staticMember ? DUP2 : DUP2_X1
                                : staticMember ? DUP : DUP_X1
                        )
                    );
                }
                addOne(type, increase);
                if (!postfix) {
                    method.with(
                        simpleInstruction(
                            slots(type) == 2
                                ? staticMember ? DUP2 : DUP2_X1
                                : staticMember ? DUP : DUP_X1
                        )
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
            final boolean spilled =
                asyncStateMachine != null && containsAwait(binary.right());
            if (spilled) {
                saveFrameTemporary(binary.left());
                expression(binary.right());
                final Type rightType =
                    semanticModel.getEffectiveType(binary.right());
                final int right = reserve(rightType);
                method.with(localInstruction(storeOpcode(rightType), right));
                loadFrameTemporary(binary.left());
                method.with(localInstruction(loadOpcode(rightType), right));
            }
            else {
                expression(binary.left());
                expression(binary.right());
            }
            if (operator == BinaryOperator.INSTANCEOF) {
                method.with(simpleInstruction(SWAP));
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc("java/lang/Class"),
                    "isInstance",
                    MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;)Z"),
                    false
                );
                if (spilled) {
                    frameTemporaries.remove(binary.left());
                }
                return;
            }
            if (operator == BinaryOperator.ADD && type == BuiltinType.STRING) {
                method.invoke(
                    INVOKEVIRTUAL,
                    classDesc("java/lang/String"),
                    "concat",
                    MethodTypeDesc
                        .ofDescriptor("(Ljava/lang/String;)Ljava/lang/String;"),
                    false
                );
                if (spilled) {
                    frameTemporaries.remove(binary.left());
                }
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
                if (spilled) {
                    frameTemporaries.remove(binary.left());
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
            if (spilled) {
                frameTemporaries.remove(binary.left());
            }
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
