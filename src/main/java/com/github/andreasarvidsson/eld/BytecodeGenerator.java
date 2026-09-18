package com.github.andreasarvidsson.eld;

import static org.objectweb.asm.Opcodes.*;

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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
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
    private @Nullable ClassWriter currentWriter;
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
                final ClassWriter writer = classWriter();
                writer.visit(
                    V21,
                    ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
                    interfaceOwner(type),
                    null,
                    "java/lang/Object",
                    null
                );
                for (final VariableSymbol field : semanticModel
                    .getInterface(type)
                    .fields()
                    .values()) {
                    writer
                        .visitMethod(
                            ACC_PUBLIC | ACC_ABSTRACT,
                            "$get$" + field.name(),
                            "()" + descriptor(field.type()),
                            null,
                            null
                        )
                        .visitEnd();
                    if (field.mutability() == Mutability.VAR) {
                        writer
                            .visitMethod(
                                ACC_PUBLIC | ACC_ABSTRACT,
                                "$set$" + field.name(),
                                "(" + descriptor(field.type()) + ")V",
                                null,
                                null
                            )
                            .visitEnd();
                    }
                }
                for (final FunctionSymbol function : semanticModel
                    .getInterface(type)
                    .methods()
                    .values()) {
                    writer
                        .visitMethod(
                            ACC_PUBLIC | ACC_ABSTRACT,
                            function.name(),
                            methodDescriptor(function.type()),
                            null,
                            null
                        )
                        .visitEnd();
                    if (hasDefaultParameters(function)) {
                        writer
                            .visitMethod(
                                ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC,
                                function.name(),
                                defaultDescriptor(function.type()),
                                null,
                                null
                            )
                            .visitEnd();
                    }
                }
                writer.visitEnd();
                classes.put(interfaceOwner(type), writer.toByteArray());
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

    private ClassWriter classWriter() {
        return new ClassWriter(
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        ) {
            @Override
            protected String getCommonSuperClass(
                final String left,
                final String right
            ) {
                // Generated classes are not available to ASM's class loader.
                if (left.equals(right)) {
                    return left;
                }
                if (
                    left.startsWith(moduleName + "$")
                        || right.startsWith(moduleName + "$")
                        || classOwners.containsValue(left)
                        || classOwners.containsValue(right)
                ) {
                    final ClassType leftClass = generatedClassType(left);
                    final ClassType rightClass = generatedClassType(right);
                    if (leftClass != null && rightClass != null) {
                        final ClassType common =
                            semanticModel
                                .commonClassType(leftClass, rightClass);
                        if (common != null) {
                            return classOwner(common);
                        }
                    }
                    final Type leftReference = generatedReferenceType(left);
                    final Type rightReference = generatedReferenceType(right);
                    if (leftReference != null && rightReference != null) {
                        if (
                            semanticModel
                                .isSubtype(leftReference, rightReference)
                        ) {
                            return typeOwner(rightReference);
                        }
                        if (
                            semanticModel
                                .isSubtype(rightReference, leftReference)
                        ) {
                            return typeOwner(leftReference);
                        }
                        for (final InterfaceType contract : semanticModel
                            .getInterfaceTypes()) {
                            if (
                                semanticModel.isSubtype(leftReference, contract)
                                    && semanticModel
                                        .isSubtype(rightReference, contract)
                            ) {
                                return interfaceOwner(contract);
                            }
                        }
                    }
                    return "java/lang/Object";
                }
                return super.getCommonSuperClass(left, right);
            }
        };
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
        final ClassWriter writer = classWriter();
        currentWriter = writer;
        writer.visit(
            V21,
            ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT,
            owner,
            classSignature(
                "java/lang/Object",
                semanticModel.getInterface(type).superInterfaces()
            ),
            "java/lang/Object",
            semanticModel.getInterface(type)
                .superInterfaces()
                .stream()
                .map(this::interfaceOwner)
                .toArray(String[]::new)
        );
        writer.visitNestHost(moduleName);
        writer.visitInnerClass(
            owner,
            moduleName,
            declaration.name().name(),
            ACC_PUBLIC | ACC_STATIC | ACC_INTERFACE | ACC_ABSTRACT
        );
        final IdentityHashMap<Symbol, String> globals = new IdentityHashMap<>();
        for (final BlockItem item : program.items()) {
            if (item instanceof VariableDeclaration variable) {
                final Symbol symbol = semanticModel.getSymbol(variable.name());
                globals.put(symbol, symbol.name());
            }
        }
        for (final var member : declaration.members()) {
            if (member instanceof UninitializedVariableDeclaration field) {
                writer
                    .visitMethod(
                        ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC,
                        "$get$" + field.name().name(),
                        "()" + descriptor(
                            semanticModel.getSymbol(field.name()).type()
                        ),
                        null,
                        null
                    )
                    .visitEnd();
                if (field.mutability() == Mutability.VAR) {
                    writer
                        .visitMethod(
                            ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC,
                            "$set$" + field.name().name(),
                            "(" + descriptor(
                                semanticModel.getSymbol(field.name()).type()
                            ) + ")V",
                            null,
                            null
                        )
                        .visitEnd();
                }
            }
            else if (member instanceof InterfaceMethodDeclaration method) {
                final FunctionType signature =
                    (FunctionType) semanticModel.getSymbol(method.name())
                        .type();
                writer
                    .visitMethod(
                        ACC_PUBLIC | ACC_ABSTRACT,
                        method.name().name(),
                        methodDescriptor(signature),
                        null,
                        null
                    )
                    .visitEnd();
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
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void generateGetter(
        final ClassWriter writer,
        final String name,
        final String fieldOwner,
        final Type type
    ) {
        final MethodVisitor getter =
            writer.visitMethod(
                ACC_PUBLIC | ACC_SYNTHETIC,
                "$get$" + name,
                "()" + descriptor(type),
                null,
                null
            );
        getter.visitCode();
        getter.visitVarInsn(ALOAD, 0);
        getter.visitFieldInsn(GETFIELD, fieldOwner, name, descriptor(type));
        getter.visitInsn(returnOpcode(type));
        getter.visitMaxs(0, 0);
        getter.visitEnd();
    }

    private void generateSetter(
        final ClassWriter writer,
        final String name,
        final String fieldOwner,
        final Type type
    ) {
        final MethodVisitor setter =
            writer.visitMethod(
                ACC_PUBLIC | ACC_SYNTHETIC,
                "$set$" + name,
                "(" + descriptor(type) + ")V",
                null,
                null
            );
        setter.visitCode();
        setter.visitVarInsn(ALOAD, 0);
        setter.visitVarInsn(loadOpcode(type), 1);
        setter.visitFieldInsn(PUTFIELD, fieldOwner, name, descriptor(type));
        setter.visitInsn(RETURN);
        setter.visitMaxs(0, 0);
        setter.visitEnd();
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
        final ClassWriter writer = classWriter();
        currentWriter = writer;
        writer.visit(
            V21,
            ACC_PUBLIC | ACC_SUPER,
            name,
            classSignature(
                superclassOwner,
                semanticModel.getImplementedInterfaces(classType)
            ),
            superclassOwner,
            semanticModel.getImplementedInterfaces(classType)
                .stream()
                .map(BytecodeGenerator.this::interfaceOwner)
                .toArray(String[]::new)
        );
        writer.visitNestHost(moduleName);
        writer.visitInnerClass(
            name,
            moduleName,
            declaration.name().name(),
            ACC_PUBLIC | ACC_STATIC
        );
        final IdentityHashMap<Symbol, String> globals = new IdentityHashMap<>();
        for (final BlockItem item : program.items()) {
            if (item instanceof VariableDeclaration variable) {
                final Symbol symbol = semanticModel.getSymbol(variable.name());
                globals.put(symbol, symbol.name());
            }
        }
        final IdentityHashMap<Symbol, String> members = new IdentityHashMap<>();
        for (final MemberDeclaration memberDeclaration : declaration
            .members()) {
            final Declaration member = memberDeclaration.declaration();
            if (member instanceof VariableDeclaration variable) {
                final Symbol symbol = semanticModel.getSymbol(variable.name());
                members.put(symbol, symbol.name());
                // Instance constants must be assigned by each constructor.
                writer
                    .visitField(
                        visibilityAccess(memberDeclaration.visibility())
                            | (variable.mutability() == Mutability.CONST
                                ? ACC_FINAL
                                : 0),
                        symbol.name(),
                        descriptor(symbol.type()),
                        fieldSignature(symbol.type()),
                        null
                    )
                    .visitEnd();
            }
            else if (member instanceof FunctionDeclaration function) {
                final Symbol symbol = semanticModel.getSymbol(function.name());
                members.put(symbol, symbol.name());
            }
            else if (member instanceof UninitializedVariableDeclaration field) {
                final Symbol symbol = semanticModel.getSymbol(field.name());
                members.put(symbol, symbol.name());
                writer
                    .visitField(
                        visibilityAccess(memberDeclaration.visibility())
                            | (field.mutability() == Mutability.CONST
                                ? ACC_FINAL
                                : 0),
                        symbol.name(),
                        descriptor(symbol.type()),
                        fieldSignature(symbol.type()),
                        null
                    )
                    .visitEnd();
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
                (ClassType) semanticModel.getSymbol(declaration.name()).type()
            );
        final MethodVisitor constructor =
            writer.visitMethod(
                visibilityAccess(
                    semanticModel.getConstructorVisibility(
                        (ClassType) semanticModel.getSymbol(declaration.name())
                            .type()
                    )
                ),
                "<init>",
                methodDescriptor(constructorType),
                null,
                null
            );
        final MethodGenerator initializer =
            new MethodGenerator(
                constructor,
                globals,
                BuiltinType.VOID,
                instance
            );
        constructor.visitCode();
        if (declarationConstructor != null) {
            for (final FunctionParameter parameter : declarationConstructor
                .parameters()) {
                initializer.local(semanticModel.getSymbol(parameter.name()));
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
        writer.visitEnd();
        return writer.toByteArray();
    }

    private record InstanceContext(
        String owner, IdentityHashMap<Symbol, String> members
    ) {
    }

    private void generateFunction(
        final ClassWriter writer,
        final FunctionDeclaration function,
        final IdentityHashMap<Symbol, String> globals,
        final @Nullable InstanceContext instance
    ) {
        final FunctionSymbol symbol =
            (FunctionSymbol) semanticModel.getSymbol(function.name());
        final MethodVisitor method =
            writer.visitMethod(
                visibilityAccess(
                    instance == null
                        ? Visibility.PUBLIC
                        : semanticModel.getMemberVisibility(symbol)
                ) | (instance == null ? ACC_STATIC : 0),
                symbol.name(),
                methodDescriptor(symbol.type()),
                methodSignature(symbol.type()),
                null
            );
        final MethodGenerator generator =
            new MethodGenerator(
                method,
                globals,
                symbol.type().returnType(),
                instance
            );
        method.visitCode();
        for (final FunctionParameter parameter : function.parameters()) {
            generator.local(semanticModel.getSymbol(parameter.name()));
        }
        generator.finish(generator.block(function.body()));
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
        final ClassWriter writer,
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
        final MethodVisitor method =
            writer.visitMethod(
                visibilityAccess(visibility) | ACC_SYNTHETIC
                    | (instance == null ? ACC_STATIC : 0),
                name,
                defaultDescriptor(type),
                null,
                null
            );
        final MethodGenerator generator =
            new MethodGenerator(method, globals, type.returnType(), instance);
        generator.beforeBaseInitialization = name.equals("<init>");
        method.visitCode();
        for (final FunctionParameter parameter : parameters) {
            generator.local(semanticModel.getSymbol(parameter.name()));
        }
        final int mask = generator.nextLocal++;
        for (int i = 0; i < parameters.size(); i++) {
            final FunctionParameter parameter = parameters.get(i);
            if (!parameter.omittable()) {
                continue;
            }
            final Label supplied = new Label();
            method.visitVarInsn(ALOAD, mask);
            method.visitLdcInsn(i);
            method.visitInsn(BALOAD);
            method.visitJumpInsn(IFEQ, supplied);
            if (parameter.defaultValue() != null) {
                generator.expression(parameter.defaultValue());
            }
            else {
                method.visitInsn(ACONST_NULL);
            }
            method.visitVarInsn(
                storeOpcode(type.parameterTypes().get(i)),
                generator.local(semanticModel.getSymbol(parameter.name()))
            );
            method.visitLabel(supplied);
        }
        if (instance != null) {
            method.visitVarInsn(ALOAD, 0);
        }
        for (final FunctionParameter parameter : parameters) {
            final Symbol symbol = semanticModel.getSymbol(parameter.name());
            method.visitVarInsn(
                loadOpcode(symbol.type()),
                generator.local(symbol)
            );
        }
        method.visitMethodInsn(
            name.equals("<init>")
                ? INVOKESPECIAL
                : instance == null
                    ? INVOKESTATIC
                    : interfaceOwnerName(instance.owner())
                        ? INVOKEINTERFACE
                        : INVOKEVIRTUAL,
            instance == null ? moduleName : instance.owner(),
            name,
            methodDescriptor(type),
            instance != null && interfaceOwnerName(instance.owner())
        );
        method.visitInsn(returnOpcode(type.returnType()));
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private byte[] generateModule() {
        final ClassWriter writer = classWriter();
        currentWriter = writer;
        for (final String objectName : objectNameOrder) {
            writer.visitNestMember(objectName);
        }
        writer.visit(
            V21,
            ACC_PUBLIC | ACC_SUPER
                | (moduleName.equals("Test") ? ACC_FINAL : 0),
            moduleName,
            null,
            parentName,
            null
        );
        final IdentityHashMap<Symbol, String> globals = new IdentityHashMap<>();
        final List<BlockItem> initializers = new ArrayList<>();
        for (final BlockItem item : program.items().subList(0, previousItems)) {
            if (item instanceof VariableDeclaration variable) {
                final Symbol symbol = semanticModel.getSymbol(variable.name());
                globals.put(symbol, symbol.name());
            }
        }
        for (final BlockItem item : program.items()
            .subList(previousItems, program.items().size())) {
            if (item instanceof VariableDeclaration variable) {
                final Symbol symbol = semanticModel.getSymbol(variable.name());
                final Expression initializer = variable.initializer();
                final Object constantValue =
                    variable.mutability() == Mutability.CONST
                        ? constantValue(initializer)
                        : null;
                if (constantValue == null) {
                    initializers.add(item);
                }
                globals.put(symbol, symbol.name());
                writer
                    .visitField(
                        ACC_PUBLIC | ACC_STATIC
                            | (moduleName.equals("Test")
                                && variable.mutability() == Mutability.CONST
                                    ? ACC_FINAL
                                    : 0),
                        symbol.name(),
                        descriptor(symbol.type()),
                        fieldSignature(symbol.type()),
                        constantValue
                    )
                    .visitEnd();
            }
            else if (item instanceof ClassDeclaration declaration) {
                final String name = className(declaration);
                writer.visitNestMember(name);
                writer.visitInnerClass(
                    name,
                    moduleName,
                    declaration.name().name(),
                    ACC_PUBLIC | ACC_STATIC
                );
            }
            else if (item instanceof InterfaceDeclaration contract) {
                final String name =
                    interfaceOwner(
                        (InterfaceType) semanticModel.getSymbol(contract.name())
                            .type()
                    );
                writer.visitNestMember(name);
                writer.visitInnerClass(
                    name,
                    moduleName,
                    contract.name().name(),
                    ACC_PUBLIC | ACC_STATIC | ACC_INTERFACE | ACC_ABSTRACT
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
            final MethodVisitor method =
                writer.visitMethod(
                    moduleName.equals("Test")
                        ? ACC_STATIC
                        : ACC_PUBLIC | ACC_STATIC,
                    moduleName.equals("Test") ? "<clinit>" : "$eval",
                    "()V",
                    null,
                    null
                );
            final MethodGenerator generator =
                new MethodGenerator(method, globals, BuiltinType.VOID);
            method.visitCode();
            boolean reachable = true;
            for (final BlockItem item : initializers) {
                if (!reachable) {
                    break;
                }
                if (
                    !moduleName.equals("Test")
                        && item instanceof ExpressionStatement statement
                        && semanticModel.getEffectiveType(
                            statement.expression()
                        ) != BuiltinType.VOID
                ) {
                    method.visitFieldInsn(
                        GETSTATIC,
                        "java/lang/System",
                        "out",
                        "Ljava/io/PrintStream;"
                    );
                    generator.expression(statement.expression());
                    final Type type =
                        semanticModel.getEffectiveType(statement.expression());
                    final String argument = printArgumentDescriptor(type);
                    method.visitMethodInsn(
                        INVOKEVIRTUAL,
                        "java/io/PrintStream",
                        "println",
                        "(" + argument + ")V",
                        false
                    );
                    continue;
                }
                reachable = generator.item(item);
            }
            generator.finish(reachable);
        }
        writer.visitEnd();
        return writer.toByteArray();
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
            return org.objectweb.asm.Type.getInternalName(type.javaClass());
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
        final ClassWriter writer,
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
            final MethodVisitor method =
                writer.visitMethod(
                    ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC,
                    function.name(),
                    erased,
                    null,
                    null
                );
            method.visitCode();
            method.visitVarInsn(ALOAD, 0);
            final MethodGenerator generator =
                new MethodGenerator(
                    method,
                    new IdentityHashMap<>(),
                    BuiltinType.I32,
                    null
                );
            for (int i = 0; i < type.parameterTypes().size(); i++) {
                method.visitVarInsn(ALOAD, i + 1);
                generator.readObject(type.parameterTypes().get(i));
            }
            method.visitMethodInsn(
                INVOKEVIRTUAL,
                owner,
                function.name(),
                methodDescriptor(type),
                false
            );
            method.visitInsn(IRETURN);
            method.visitMaxs(0, 0);
            method.visitEnd();
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
                org.objectweb.asm.Type.getType(source).getInternalName()
            );
        final Type targetClass =
            generatedReferenceType(
                org.objectweb.asm.Type.getType(target).getInternalName()
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

    private int opcode(final Type type, final int base) {
        return org.objectweb.asm.Type.getType(descriptor(type)).getOpcode(base);
    }

    private static int slots(final Type type) {
        return type == BuiltinType.I64 || type == BuiltinType.F64 ? 2 : 1;
    }

    private int loadOpcode(final Type type) {
        return opcode(type, ILOAD);
    }

    private int storeOpcode(final Type type) {
        return opcode(type, ISTORE);
    }

    private int returnOpcode(final Type type) {
        return opcode(type, IRETURN);
    }

    private int arrayStoreOpcode(final Type type) {
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
        private final MethodVisitor method;
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
            final MethodVisitor method,
            final IdentityHashMap<Symbol, String> globals,
            final Type returnType
        ) {
            this(method, globals, returnType, null);
        }

        private MethodGenerator(
            final MethodVisitor method,
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
                method.visitInsn(RETURN);
            }
            else if (reachable) {
                // The semantic analyzer does not yet prove that every path returns.
                // Fail explicitly on fallthrough, while keeping the class verifiable.
                method.visitTypeInsn(NEW, "java/lang/IllegalStateException");
                method.visitInsn(DUP);
                method.visitLdcInsn(
                    "Function completed without returning a value"
                );
                method.visitMethodInsn(
                    INVOKESPECIAL,
                    "java/lang/IllegalStateException",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false
                );
                method.visitInsn(ATHROW);
            }
            method.visitMaxs(0, 0);
            method.visitEnd();
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
            method.visitVarInsn(ALOAD, 0);
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
            method.visitMethodInsn(
                INVOKESPECIAL,
                constructorSuperclass == null
                    ? "java/lang/Object"
                    : classOwner(constructorSuperclass),
                "<init>",
                omitted
                    ? defaultDescriptor(Objects.requireNonNull(type))
                    : type == null ? "()V" : methodDescriptor(type),
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
                        method.visitInsn(ICONST_1);
                        if (reference(symbol.type())) {
                            method.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                        }
                        else {
                            method.visitIntInsn(
                                NEWARRAY,
                                RuntimeAbi.array(symbol.type()).creationOpcode
                            );
                        }
                        method.visitVarInsn(ASTORE, local(symbol));
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
                        return conditional(conditional, new Label());
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
                    method.visitJumpInsn(GOTO, target.label());
                    return false;
                }
                case ReturnStatement statement -> {
                    final Expression value = statement.value();
                    if (value != null) {
                        expression(value);
                    }
                    method.visitInsn(returnOpcode(returnType));
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
                    final Label condition = new Label();
                    final Label end = new Label();
                    method.visitLabel(condition);
                    if (!alwaysTrue) {
                        expression(statement.condition());
                        method.visitJumpInsn(IFEQ, end);
                    }
                    final Loop loop = new Loop(condition, end);
                    if (loopBody(statement.body(), loop)) {
                        method.visitJumpInsn(GOTO, condition);
                    }
                    method.visitLabel(end);
                    return !alwaysTrue || loop.hasBreak;
                }
                case DoWhileStatement statement -> {
                    final Label start = new Label();
                    final Label condition = new Label();
                    final Label end = new Label();
                    method.visitLabel(start);
                    final Loop loop = new Loop(condition, end);
                    final boolean reachesCondition =
                        loopBody(statement.body(), loop) || loop.hasContinue;
                    if (reachesCondition) {
                        method.visitLabel(condition);
                        if (
                            constructorSuperclass != null
                                && isBooleanLiteral(statement.condition(), true)
                        ) {
                            method.visitJumpInsn(GOTO, start);
                        }
                        else if (
                            constructorSuperclass == null || !isBooleanLiteral(
                                statement.condition(),
                                false
                            )
                        ) {
                            expression(statement.condition());
                            method.visitJumpInsn(IFNE, start);
                        }
                    }
                    method.visitLabel(end);
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
                    final Label start = new Label();
                    final Label next = new Label();
                    final Label end = new Label();
                    method.visitLabel(start);
                    if (condition != null && !alwaysTrue) {
                        expression(condition);
                        method.visitJumpInsn(IFEQ, end);
                    }
                    final Loop loop = new Loop(next, end);
                    if (loopBody(statement.body(), loop) || loop.hasContinue) {
                        method.visitLabel(next);
                        if (update != null) {
                            discard(update);
                        }
                        method.visitJumpInsn(GOTO, start);
                    }
                    method.visitLabel(end);
                    return !alwaysTrue || loop.hasBreak;
                }
                case ForEachStatement statement -> forEach(statement);
                case BreakStatement statement -> {
                    if (loops.isEmpty()) {
                        throw unsupported(statement, "Break outside a loop");
                    }
                    loops.element().hasBreak = true;
                    method.visitJumpInsn(GOTO, loops.element().breakTarget);
                    return false;
                }
                case ContinueStatement statement -> {
                    if (loops.isEmpty()) {
                        throw unsupported(statement, "Continue outside a loop");
                    }
                    loops.element().hasContinue = true;
                    method.visitJumpInsn(GOTO, loops.element().continueTarget);
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
            method.visitVarInsn(storeOpcode(subjectType), subject);
            final Label end = new Label();
            yieldTargets.push(new YieldTarget(end, discarded));
            for (final SwitchBranch branch : selection.branches()) {
                final Label body = new Label();
                final Label next = new Label();
                final List<Expression> matches = branch.matches();
                if (matches != null) {
                    for (final Expression match : matches) {
                        method.visitVarInsn(loadOpcode(subjectType), subject);
                        expression(match);
                        if (
                            subjectType == BuiltinType.STRING
                                || subjectType instanceof UnionType
                                || subjectType == BuiltinType.ANY
                        ) {
                            method.visitMethodInsn(
                                INVOKESTATIC,
                                "java/util/Objects",
                                "equals",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                                false
                            );
                            method.visitJumpInsn(IFNE, body);
                        }
                        else if (
                            subjectType == BuiltinType.F32
                                || subjectType == BuiltinType.F64
                                || subjectType == BuiltinType.I64
                        ) {
                            method.visitInsn(
                                subjectType == BuiltinType.I64
                                    ? LCMP
                                    : subjectType == BuiltinType.F64
                                        ? DCMPL
                                        : FCMPL
                            );
                            method.visitJumpInsn(IFEQ, body);
                        }
                        else {
                            method.visitJumpInsn(
                                reference(subjectType) ? IF_ACMPEQ : IF_ICMPEQ,
                                body
                            );
                        }
                    }
                }
                method.visitJumpInsn(GOTO, next);
                method.visitLabel(body);
                if (selectionBody(branch.body(), discarded)) {
                    method.visitJumpInsn(GOTO, end);
                }
                method.visitLabel(next);
            }
            final SwitchElseBranch otherwise = selection.elseBranch();
            if (otherwise != null) {
                selectionBody(otherwise.body(), discarded);
            }
            method.visitLabel(end);
            yieldTargets.pop();
        }

        private boolean integerSelection(
            final SwitchExpression selection,
            final boolean discarded
        ) {
            final TreeMap<Integer, Label> targets = new TreeMap<>();
            final List<Label> bodies = new ArrayList<>();
            for (final SwitchBranch branch : selection.branches()) {
                final Label body = new Label();
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
            final Label end = new Label();
            final Label otherwise = new Label();
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
                method.visitTableSwitchInsn(low, high, otherwise, labels);
            }
            else {
                final int[] keys =
                    targets.keySet()
                        .stream()
                        .mapToInt(Integer::intValue)
                        .toArray();
                method.visitLookupSwitchInsn(
                    otherwise,
                    keys,
                    targets.values().toArray(Label[]::new)
                );
            }
            yieldTargets.push(new YieldTarget(end, discarded));
            for (int index = 0; index < selection.branches().size(); index++) {
                method.visitLabel(bodies.get(index));
                if (
                    selectionBody(
                        selection.branches().get(index).body(),
                        discarded
                    )
                ) {
                    method.visitJumpInsn(GOTO, end);
                }
            }
            method.visitLabel(otherwise);
            final SwitchElseBranch elseBranch = selection.elseBranch();
            if (elseBranch != null) {
                selectionBody(elseBranch.body(), discarded);
            }
            method.visitLabel(end);
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
            method.visitLabel(end);
            return otherwise == null || reachable;
        }

        private boolean branch(
            final Expression condition,
            final BlockStatement body,
            final Label end
        ) {
            final Label next = new Label();
            expression(condition);
            method.visitJumpInsn(IFEQ, next);
            final boolean reachable = block(body);
            if (reachable) {
                method.visitJumpInsn(GOTO, end);
            }
            method.visitLabel(next);
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
            method.visitVarInsn(ASTORE, array);
            method.visitInsn(ICONST_0);
            method.visitVarInsn(ISTORE, index);
            final Label start = new Label();
            final Label next = new Label();
            final Label end = new Label();
            method.visitLabel(start);
            method.visitVarInsn(ILOAD, index);
            method.visitVarInsn(ALOAD, array);
            arrayCall(value.type(), RuntimeAbi.ArrayMethod.SIZE);
            method.visitJumpInsn(IF_ICMPGE, end);
            method.visitVarInsn(ALOAD, array);
            method.visitVarInsn(ILOAD, index);
            arrayGet(value.type());
            store(value);
            final IdentifierDeclaration indexName = statement.index();
            if (indexName != null) {
                // Copy into the source-level const binding; only the hidden
                // loop counter is incremented by compiler-generated code.
                method.visitVarInsn(ILOAD, index);
                store(semanticModel.getSymbol(indexName));
            }
            final Loop loop = new Loop(next, end);
            if (loopBody(statement.body(), loop) || loop.hasContinue) {
                method.visitLabel(next);
                method.visitIincInsn(index, 1);
                method.visitJumpInsn(GOTO, start);
            }
            method.visitLabel(end);
        }

        private void load(final Symbol symbol) {
            if (BuiltinFunctionSymbol.PRINT.equals(symbol)) {
                method.visitFieldInsn(
                    GETSTATIC,
                    "java/lang/System",
                    "out",
                    "Ljava/io/PrintStream;"
                );
            }
            else if (symbol instanceof FunctionSymbol function) {
                final boolean instanceMethod =
                    instance != null && instance.members().containsKey(symbol);
                method.visitLdcInsn(
                    new Handle(
                        instanceMethod ? H_INVOKEVIRTUAL : H_INVOKESTATIC,
                        instanceMethod
                            ? Objects.requireNonNull(instance).owner()
                            : moduleName,
                        symbol.name(),
                        methodDescriptor(function.type()),
                        false
                    )
                );
                if (instanceMethod) {
                    method.visitVarInsn(ALOAD, 0);
                    method.visitMethodInsn(
                        INVOKEVIRTUAL,
                        "java/lang/invoke/MethodHandle",
                        "bindTo",
                        "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;",
                        false
                    );
                }
            }
            else if (
                instance != null && instance.members().containsKey(symbol)
            ) {
                method.visitVarInsn(ALOAD, 0);
                method.visitFieldInsn(
                    GETFIELD,
                    instance.owner(),
                    Objects.requireNonNull(instance.members().get(symbol)),
                    descriptor(symbol.type())
                );
            }
            else if (captureFields.containsKey(symbol)) {
                loadCaptureStorage(symbol);
                if (cell(symbol)) {
                    method.visitInsn(ICONST_0);
                    method.visitInsn(opcode(symbol.type(), IALOAD));
                    if (reference(symbol.type())) {
                        readObject(symbol.type());
                    }
                }
            }
            else if (globals.containsKey(symbol)) {
                method.visitFieldInsn(
                    GETSTATIC,
                    moduleName,
                    Objects.requireNonNull(globals.get(symbol)),
                    descriptor(symbol.type())
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
                    method.visitVarInsn(ALOAD, slot);
                    method.visitInsn(ICONST_0);
                    method.visitInsn(opcode(symbol.type(), IALOAD));
                    if (reference(symbol.type())) {
                        readObject(symbol.type());
                    }
                }
                else {
                    method.visitVarInsn(loadOpcode(symbol.type()), slot);
                }
            }
        }

        private boolean prepareStore(final Symbol symbol) {
            if (instance != null && instance.members().containsKey(symbol)) {
                method.visitVarInsn(ALOAD, 0);
                return true;
            }
            return false;
        }

        // Instance stores expect the receiver below the value on the stack.
        private void store(final Symbol symbol) {
            if (cell(symbol)) {
                final int value = nextLocal;
                nextLocal += slots(symbol.type());
                method.visitVarInsn(storeOpcode(symbol.type()), value);
                loadCaptureStorage(symbol);
                method.visitInsn(ICONST_0);
                method.visitVarInsn(loadOpcode(symbol.type()), value);
                method.visitInsn(arrayStoreOpcode(symbol.type()));
                return;
            }
            if (instance != null && instance.members().containsKey(symbol)) {
                method.visitFieldInsn(
                    PUTFIELD,
                    instance.owner(),
                    Objects.requireNonNull(instance.members().get(symbol)),
                    descriptor(symbol.type())
                );
            }
            else if (globals.containsKey(symbol)) {
                method.visitFieldInsn(
                    PUTSTATIC,
                    moduleName,
                    Objects.requireNonNull(globals.get(symbol)),
                    descriptor(symbol.type())
                );
            }
            else {
                method.visitVarInsn(storeOpcode(symbol.type()), local(symbol));
            }
        }

        private void discard(final Expression expression) {
            expression(expression);
            if (
                semanticModel.getEffectiveType(expression) != BuiltinType.VOID
            ) {
                method.visitInsn(
                    slots(semanticModel.getEffectiveType(expression)) == 2
                        ? POP2
                        : POP
                );
            }
        }

        private void expression(final Expression expression) {
            final Integer temporary = expressionTemporaries.get(expression);
            if (temporary != null) {
                method.visitVarInsn(
                    loadOpcode(semanticModel.getEffectiveType(expression)),
                    temporary
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
                        method.visitLdcInsn(value.longValueExact());
                    }
                    else {
                        method.visitLdcInsn(value.intValueExact());
                    }
                    convertExpression(expression);
                    return;
                }
                method
                    .visitLdcInsn(Objects.requireNonNull(constantValue(unary)));
                return;
            }
            switch (expression) {
                case FormatStringExpression format -> {
                    method.visitTypeInsn(NEW, "java/lang/StringBuilder");
                    method.visitInsn(DUP);
                    method.visitMethodInsn(
                        INVOKESPECIAL,
                        "java/lang/StringBuilder",
                        "<init>",
                        "()V",
                        false
                    );
                    for (final Expression part : format.parts()) {
                        expression(part);
                        final String argument =
                            printArgumentDescriptor(
                                semanticModel.getEffectiveType(part)
                            );
                        method.visitMethodInsn(
                            INVOKEVIRTUAL,
                            "java/lang/StringBuilder",
                            "append",
                            "(" + argument + ")Ljava/lang/StringBuilder;",
                            false
                        );
                    }
                    method.visitMethodInsn(
                        INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "toString",
                        "()Ljava/lang/String;",
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
                            method.visitInsn(
                                opcode(
                                    semanticModel.getExpressionType(unary),
                                    INEG
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
                    final Label otherwise = new Label();
                    final Label end = new Label();
                    expression(ternary.condition());
                    method.visitJumpInsn(IFEQ, otherwise);
                    expression(ternary.thenBranch());
                    method.visitJumpInsn(GOTO, end);
                    method.visitLabel(otherwise);
                    expression(ternary.elseBranch());
                    method.visitLabel(end);
                }
                case IfExpression conditional -> {
                    final Label end = new Label();
                    yieldTargets.push(new YieldTarget(end, false));
                    conditional(conditional, end);
                    yieldTargets.pop();
                }
                case SwitchExpression selection -> selection(selection);
                case AssignmentExpression assignment -> assign(assignment);
                case CallExpression call -> call(call);
                case MemberExpression member -> member(member);
                case ThisExpression self -> {
                    method.visitVarInsn(ALOAD, 0);
                    if (lexicalReceiverOwner != null) {
                        method.visitFieldInsn(
                            GETFIELD,
                            Objects.requireNonNull(instance).owner(),
                            "$receiver",
                            "L" + lexicalReceiverOwner + ";"
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
                        method.visitTypeInsn(NEW, owner);
                        method.visitInsn(DUP);
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
                        method.visitMethodInsn(
                            INVOKESPECIAL,
                            owner,
                            "<init>",
                            org.objectweb.asm.Type
                                .getConstructorDescriptor(constructor),
                            false
                        );
                        break;
                    }
                    final String name =
                        ((ClassType) semanticModel.getExpressionType(creation))
                            .name();
                    final String owner =
                        classOwners.getOrDefault(name, moduleName + "$" + name);
                    method.visitTypeInsn(NEW, owner);
                    method.visitInsn(DUP);
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
                    method.visitMethodInsn(
                        INVOKESPECIAL,
                        owner,
                        "<init>",
                        omitted
                            ? defaultDescriptor(constructorType)
                            : methodDescriptor(constructorType),
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
                    method.visitTypeInsn(
                        CHECKCAST,
                        org.objectweb.asm.Type.getType(target).getInternalName()
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
                method.visitTypeInsn(CHECKCAST, owner);
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
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    owner,
                    valueMethod,
                    "()" + descriptor(type),
                    false
                );
            }
            else if (!descriptor(type).equals("Ljava/lang/Object;")) {
                method.visitTypeInsn(
                    CHECKCAST,
                    org.objectweb.asm.Type.getType(descriptor(type))
                        .getInternalName()
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
                method.visitVarInsn(ALOAD, 0);
                method.visitFieldInsn(
                    GETFIELD,
                    Objects.requireNonNull(instance).owner(),
                    Objects.requireNonNull(captureFields.get(symbol)),
                    captureDescriptor(symbol)
                );
            }
            else if (cell(symbol)) {
                method.visitVarInsn(ALOAD, local(symbol));
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

                final ClassWriter saved = currentWriter;
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
                        method.visitVarInsn(ASTORE, slot);
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
                        method.visitVarInsn(storeOpcode(valueType), slot);
                        values.put(member.value(), slot);
                    }
                }
            }
            method.visitTypeInsn(NEW, info.owner());
            method.visitInsn(DUP);
            for (final ObjectMember member : semanticModel
                .getObjectMembers(object)) {
                if (contract.fields().containsKey(member.name().name())) {
                    if (values.containsKey(member.value())) {
                        method.visitVarInsn(
                            loadOpcode(
                                semanticModel.getEffectiveType(member.value())
                            ),
                            Objects.requireNonNull(values.get(member.value()))
                        );
                    }
                    else {
                        expression(member.value());
                    }
                }
                else if (semanticModel.isSpreadMethod(member)) {
                    method
                        .visitVarInsn(
                            ALOAD,
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
                method.visitVarInsn(ALOAD, 0);
                if (lexicalReceiverOwner != null) {
                    method.visitFieldInsn(
                        GETFIELD,
                        Objects.requireNonNull(instance).owner(),
                        "$receiver",
                        "L" + lexicalReceiverOwner + ";"
                    );
                }
            }
            method.visitMethodInsn(
                INVOKESPECIAL,
                info.owner(),
                "<init>",
                info.constructorDescriptor(),
                false
            );
        }

        private byte[] generateObject(
            final ObjectExpression object,
            final InterfaceContract contract,
            final ObjectInfo info,
            final @Nullable String receiverOwner
        ) {
            final ClassWriter writer = classWriter();
            currentWriter = writer;
            writer.visit(
                V21,
                ACC_FINAL | ACC_SUPER | ACC_SYNTHETIC,
                info.owner(),
                classSignature(
                    "java/lang/Object",
                    List.of(
                        (InterfaceType) semanticModel.getExpressionType(object)
                    )
                ),
                "java/lang/Object",
                new String[] {interfaceOwner(
                    (InterfaceType) semanticModel.getExpressionType(object)
                )}
            );
            writer.visitNestHost(moduleName);
            final Map<String, String> constructorFields = new LinkedHashMap<>();
            for (final ObjectMember member : semanticModel
                .getObjectMembers(object)) {
                final VariableSymbol field =
                    contract.fields().get(member.name().name());
                if (field != null) {
                    writer
                        .visitField(
                            ACC_PRIVATE
                                | (field.mutability() == Mutability.CONST
                                    ? ACC_FINAL
                                    : 0),
                            field.name(),
                            descriptor(field.type()),
                            null,
                            null
                        )
                        .visitEnd();
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
                    writer
                        .visitField(
                            ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC,
                            name,
                            sourceDescriptor,
                            null,
                            null
                        )
                        .visitEnd();
                    constructorFields.put(name, sourceDescriptor);
                }
            }
            final IdentityHashMap<Symbol, String> fields =
                new IdentityHashMap<>();
            for (int i = 0; i < info.captures().size(); i++) {
                final Symbol capture = info.captures().get(i);
                final String name = "$capture" + i;
                fields.put(capture, name);
                writer
                    .visitField(
                        ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC,
                        name,
                        captureDescriptor(capture),
                        null,
                        null
                    )
                    .visitEnd();
                constructorFields.put(name, captureDescriptor(capture));
            }
            if (receiverOwner != null) {
                writer
                    .visitField(
                        ACC_PRIVATE | ACC_FINAL | ACC_SYNTHETIC,
                        "$receiver",
                        "L" + receiverOwner + ";",
                        null,
                        null
                    )
                    .visitEnd();
                constructorFields.put("$receiver", "L" + receiverOwner + ";");
            }
            final MethodVisitor constructor =
                writer.visitMethod(
                    ACC_PUBLIC,
                    "<init>",
                    info.constructorDescriptor(),
                    null,
                    null
                );
            constructor.visitCode();
            constructor.visitVarInsn(ALOAD, 0);
            constructor.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false
            );
            int slot = 1;
            for (final var field : constructorFields.entrySet()) {
                final org.objectweb.asm.Type fieldType =
                    org.objectweb.asm.Type.getType(field.getValue());
                constructor.visitVarInsn(ALOAD, 0);
                constructor.visitVarInsn(fieldType.getOpcode(ILOAD), slot);
                constructor.visitFieldInsn(
                    PUTFIELD,
                    info.owner(),
                    field.getKey(),
                    field.getValue()
                );
                slot += fieldType.getSize();
            }
            constructor.visitInsn(RETURN);
            constructor.visitMaxs(0, 0);
            constructor.visitEnd();
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
                    final MethodVisitor body =
                        writer.visitMethod(
                            ACC_PUBLIC,
                            member.name().name(),
                            methodDescriptor(signature),
                            null,
                            null
                        );
                    body.visitCode();
                    body.visitVarInsn(ALOAD, 0);
                    body.visitFieldInsn(
                        GETFIELD,
                        info.owner(),
                        "$delegate$" + member.name().name(),
                        descriptor(source)
                    );
                    int parameterSlot = 1;
                    for (final Type parameter : signature.parameterTypes()) {
                        body.visitVarInsn(loadOpcode(parameter), parameterSlot);
                        parameterSlot += slots(parameter);
                    }
                    body.visitMethodInsn(
                        source instanceof InterfaceType
                            ? INVOKEINTERFACE
                            : INVOKEVIRTUAL,
                        typeOwner(source),
                        member.name().name(),
                        methodDescriptor(signature),
                        source instanceof InterfaceType
                    );
                    body.visitInsn(returnOpcode(signature.returnType()));
                    body.visitMaxs(0, 0);
                    body.visitEnd();
                    // Named interfaces already supply their own default overloads.
                    // Inferred contracts retain the source method's defaults instead.
                    if (
                        ((InterfaceType) semanticModel
                            .getExpressionType(object)).name()
                            .startsWith("$spread")
                            && hasDefaultParameters(implementation)
                    ) {
                        final MethodVisitor defaults =
                            writer.visitMethod(
                                ACC_PUBLIC | ACC_SYNTHETIC,
                                member.name().name(),
                                defaultDescriptor(signature),
                                null,
                                null
                            );
                        defaults.visitCode();
                        defaults.visitVarInsn(ALOAD, 0);
                        defaults.visitFieldInsn(
                            GETFIELD,
                            info.owner(),
                            "$delegate$" + member.name().name(),
                            descriptor(source)
                        );
                        int defaultSlot = 1;
                        for (final Type parameter : signature
                            .parameterTypes()) {
                            defaults.visitVarInsn(
                                loadOpcode(parameter),
                                defaultSlot
                            );
                            defaultSlot += slots(parameter);
                        }
                        defaults.visitVarInsn(ALOAD, defaultSlot);
                        defaults.visitMethodInsn(
                            source instanceof InterfaceType
                                ? INVOKEINTERFACE
                                : INVOKEVIRTUAL,
                            typeOwner(source),
                            member.name().name(),
                            defaultDescriptor(signature),
                            source instanceof InterfaceType
                        );
                        defaults
                            .visitInsn(returnOpcode(signature.returnType()));
                        defaults.visitMaxs(0, 0);
                        defaults.visitEnd();
                    }
                    continue;
                }
                final LambdaExpression lambda =
                    (LambdaExpression) unwrap(member.value());
                final FunctionType signature = implementation.type();
                final MethodVisitor body =
                    writer.visitMethod(
                        ACC_PUBLIC,
                        member.name().name(),
                        methodDescriptor(signature),
                        null,
                        null
                    );
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
                body.visitCode();
                for (final LambdaParameter parameter : lambda.parameters()) {
                    generator.local(semanticModel.getSymbol(parameter.name()));
                }
                if (lambda.body() instanceof Expression expression) {
                    generator.expression(expression);
                    body.visitInsn(returnOpcode(signature.returnType()));
                    generator.finish(false);
                }
                else {
                    generator.finish(
                        generator.block((BlockStatement) lambda.body())
                    );
                }
            }
            generateJavaBridges(
                writer,
                info.owner(),
                List.of((InterfaceType) semanticModel.getExpressionType(object))
            );
            writer.visitEnd();
            return writer.toByteArray();
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
                method.visitLdcInsn(
                    org.objectweb.asm.Type.getType(java.util.Comparator.class)
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
            final MethodVisitor body =
                Objects.requireNonNull(currentWriter)
                    .visitMethod(
                        ACC_PRIVATE | ACC_SYNTHETIC
                            | (lambdaInstance == null ? ACC_STATIC : 0),
                        name,
                        signature.toString(),
                        null,
                        null
                    );
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
            body.visitCode();
            for (final Symbol capture : captures) {
                generator.local(capture);
            }
            for (final LambdaParameter parameter : lambda.parameters()) {
                generator.local(semanticModel.getSymbol(parameter.name()));
            }
            if (lambda.body() instanceof Expression expression) {
                generator.expression(expression);
                body.visitInsn(returnOpcode(type.returnType()));
                generator.finish(false);
            }
            else {
                generator
                    .finish(generator.block((BlockStatement) lambda.body()));
            }
            method.visitLdcInsn(
                new Handle(
                    lambdaInstance == null ? H_INVOKESTATIC : H_INVOKEVIRTUAL,
                    owner,
                    name,
                    signature.toString(),
                    false
                )
            );
            if (lambdaInstance != null) {
                method.visitVarInsn(ALOAD, 0);
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle",
                    "bindTo",
                    "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;",
                    false
                );
            }
            if (!captures.isEmpty()) {
                method.visitInsn(ICONST_0);
                method.visitLdcInsn(captures.size());
                method.visitTypeInsn(ANEWARRAY, "java/lang/Object");
                for (int i = 0; i < captures.size(); i++) {
                    final Symbol capture = captures.get(i);
                    method.visitInsn(DUP);
                    method.visitLdcInsn(i);
                    if (cell(capture)) {
                        method.visitVarInsn(ALOAD, local(capture));
                    }
                    else {
                        load(capture);
                        box(capture.type());
                    }
                    method.visitInsn(AASTORE);
                }
                method.visitMethodInsn(
                    INVOKESTATIC,
                    "java/lang/invoke/MethodHandles",
                    "insertArguments",
                    "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;",
                    false
                );
            }
            if (comparator != null) {
                method.visitMethodInsn(
                    INVOKESTATIC,
                    "java/lang/invoke/MethodHandleProxies",
                    "asInterfaceInstance",
                    "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;",
                    false
                );
                method.visitTypeInsn(CHECKCAST, "java/util/Comparator");
            }
        }

        private void box(final Type type) {
            final String owner = boxedOwner(type);
            if (owner != null) {
                method.visitMethodInsn(
                    INVOKESTATIC,
                    owner,
                    "valueOf",
                    "(" + descriptor(type) + ")L" + owner + ";",
                    false
                );
            }
        }

        private void narrow(final Type type) {
            if (type == BuiltinType.I8) {
                method.visitInsn(I2B);
            }
            if (type == BuiltinType.I16) {
                method.visitInsn(I2S);
            }
        }

        private void convert(final Type from, final Type to) {
            if (from.equals(to)) {
                return;
            }
            if (from == BuiltinType.I64) {
                if (to == BuiltinType.F32) {
                    method.visitInsn(L2F);
                }
                else if (to == BuiltinType.F64) {
                    method.visitInsn(L2D);
                }
                else {
                    method.visitInsn(L2I);
                }
            }
            else if (from == BuiltinType.F64 && to == BuiltinType.F32) {
                method.visitInsn(D2F);
            }
            else if (from == BuiltinType.F32 && to == BuiltinType.F64) {
                method.visitInsn(F2D);
            }
            else if (
                from instanceof BuiltinType builtin
                    && (builtin.isInteger() || builtin == BuiltinType.CHAR)
            ) {
                if (to == BuiltinType.I64) {
                    method.visitInsn(I2L);
                }
                if (to == BuiltinType.F32) {
                    method.visitInsn(I2F);
                }
                if (to == BuiltinType.F64) {
                    method.visitInsn(I2D);
                }
            }
            narrow(to);
        }

        private void literal(final LiteralExpression literal) {
            final String text = literal.text();
            switch (literal.kind()) {
                case INT -> method.visitLdcInsn(integerConstant(literal));
                case FLOAT -> method.visitLdcInsn(floatingConstant(literal));
                case BOOL -> method.visitInsn(
                    Boolean.parseBoolean(text) ? ICONST_1 : ICONST_0
                );
                case NULL -> method.visitInsn(ACONST_NULL);
                case CHAR -> method.visitLdcInsn((int) decodeChar(text));
                case STRING -> method.visitLdcInsn(decodeString(text));
                case RAW_STRING ->
                    method.visitLdcInsn(text.substring(1, text.length() - 1));
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
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    RuntimeAbi.array(array.elementType()).owner,
                    "sort",
                    "()V",
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
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/io/PrintStream",
                    "println",
                    "(" + argumentDescriptor + ")V",
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
                expression(member.target());
                box(semanticModel.getEffectiveType(member.target()));
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
                    method.visitInsn(ACONST_NULL);
                }
                final boolean isInterface =
                    javaMethod.getDeclaringClass().isInterface();
                method.visitMethodInsn(
                    isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL,
                    org.objectweb.asm.Type
                        .getInternalName(javaMethod.getDeclaringClass()),
                    function.name(),
                    org.objectweb.asm.Type.getMethodDescriptor(javaMethod),
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
                method.visitMethodInsn(
                    semanticModel
                        .getMemberOwner(member) instanceof InterfaceType
                            ? INVOKEINTERFACE
                            : INVOKEVIRTUAL,
                    memberOwner(member),
                    function.name(),
                    callDescriptor(call, function.type()),
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
                    method.visitVarInsn(ALOAD, 0);
                }
                callArguments(call);
                method.visitMethodInsn(
                    instanceMethod ? INVOKEVIRTUAL : INVOKESTATIC,
                    instanceMethod
                        ? Objects.requireNonNull(instance).owner()
                        : moduleName,
                    function.name(),
                    callDescriptor(call, type),
                    false
                );
            }
            else {
                expression(call.callee());
                callArguments(call);
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle",
                    "invokeExact",
                    methodDescriptor(type),
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
                case "J" -> method.visitInsn(LCONST_0);
                case "F" -> method.visitInsn(FCONST_0);
                case "D" -> method.visitInsn(DCONST_0);
                case "I", "B", "S", "C", "Z" -> method.visitInsn(ICONST_0);
                default -> method.visitInsn(ACONST_NULL);
            }
        }

        private void omissionMask(final boolean[] assigned) {
            method.visitLdcInsn(assigned.length);
            method.visitIntInsn(NEWARRAY, T_BOOLEAN);
            for (int i = 0; i < assigned.length; i++) {
                if (!assigned[i]) {
                    method.visitInsn(DUP);
                    method.visitLdcInsn(i);
                    method.visitInsn(ICONST_1);
                    method.visitInsn(BASTORE);
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
                method.visitVarInsn(storeOpcode(parameterType), slot);
            }
            for (int i = 0; i < locals.length; i++) {
                if (assigned[i]) {
                    method.visitVarInsn(
                        loadOpcode(type.parameterTypes().get(i)),
                        locals[i]
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
            method.visitTypeInsn(NEW, owner);
            method.visitInsn(DUP);
            method.visitLdcInsn(tuple.elements().size());
            method.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            for (int i = 0; i < tuple.elements().size(); i++) {
                final Expression element = tuple.elements().get(i);
                method.visitInsn(DUP);
                method.visitLdcInsn(i);
                expression(element);
                box(semanticModel.getEffectiveType(element));
                method.visitInsn(AASTORE);
            }
            method.visitMethodInsn(
                INVOKESPECIAL,
                owner,
                "<init>",
                "([Ljava/lang/Object;)V",
                false
            );
        }

        private void tupleGet(final SubscriptExpression index) {
            expression(index.target());
            method.visitLdcInsn(
                Objects
                    .requireNonNull(
                        SemanticAnalyzer.integerLiteral(index.index())
                    )
                    .intValue()
            );
            method.visitMethodInsn(
                INVOKEVIRTUAL,
                "com/github/andreasarvidsson/eld/runtime/EldTuple",
                "get",
                "(I)Ljava/lang/Object;",
                false
            );
            final Type element = semanticModel.getExpressionType(index);
            final String owner = boxedOwner(element);
            if (owner != null) {
                method.visitTypeInsn(CHECKCAST, owner);
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
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    owner,
                    valueMethod,
                    "()" + descriptor(element),
                    false
                );
            }
            else if (!descriptor(element).equals("Ljava/lang/Object;")) {
                method.visitTypeInsn(
                    CHECKCAST,
                    org.objectweb.asm.Type.getType(descriptor(element))
                        .getInternalName()
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
            method.visitInsn(ICONST_0);
            method.visitVarInsn(ISTORE, total);
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
                    method.visitVarInsn(ASTORE, value);
                    final int length = nextLocal++;
                    method.visitVarInsn(ALOAD, value);
                    arrayCall(sourceElement, RuntimeAbi.ArrayMethod.SIZE);
                    method.visitVarInsn(ISTORE, length);
                    lengths.add(length);
                    method.visitVarInsn(ILOAD, total);
                    method.visitVarInsn(ILOAD, length);
                }
                else {
                    expression(entry);
                    nextLocal += slots(element);
                    method.visitVarInsn(storeOpcode(element), value);
                    lengths.add(-1);
                    method.visitVarInsn(ILOAD, total);
                    method.visitInsn(ICONST_1);
                }
                values.add(value);
                method.visitInsn(IADD);
                method.visitVarInsn(ISTORE, total);
            }
            final int result = nextLocal++;
            method.visitVarInsn(ILOAD, total);
            if (runtime == RuntimeAbi.ArrayKind.OBJECT) {
                method.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            }
            else {
                method.visitIntInsn(NEWARRAY, runtime.creationOpcode);
            }
            method.visitVarInsn(ASTORE, result);
            final int offset = nextLocal++;
            method.visitInsn(ICONST_0);
            method.visitVarInsn(ISTORE, offset);
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
                        method.visitVarInsn(ALOAD, values.get(i));
                        method.visitVarInsn(ALOAD, result);
                        method.visitVarInsn(ILOAD, offset);
                        method.visitVarInsn(ILOAD, lengths.get(i));
                        method.visitMethodInsn(
                            INVOKEVIRTUAL,
                            sourceRuntime.owner,
                            "copyTo",
                            "(" + runtime.backingDescriptor + "II)V",
                            false
                        );
                    }
                    else {
                        // Widening and boxing require element conversion; identical storage uses arraycopy.
                        final int index = nextLocal++;
                        method.visitInsn(ICONST_0);
                        method.visitVarInsn(ISTORE, index);
                        final Label start = new Label();
                        final Label end = new Label();
                        method.visitLabel(start);
                        method.visitVarInsn(ILOAD, index);
                        method.visitVarInsn(ILOAD, lengths.get(i));
                        method.visitJumpInsn(IF_ICMPGE, end);
                        method.visitVarInsn(ALOAD, result);
                        method.visitVarInsn(ILOAD, offset);
                        method.visitVarInsn(ILOAD, index);
                        method.visitInsn(IADD);
                        method.visitVarInsn(ALOAD, values.get(i));
                        method.visitVarInsn(ILOAD, index);
                        arrayGet(sourceElement);
                        convertExpression(spread);
                        method.visitInsn(arrayStoreOpcode(element));
                        method.visitIincInsn(index, 1);
                        method.visitJumpInsn(GOTO, start);
                        method.visitLabel(end);
                    }
                    method.visitVarInsn(ILOAD, offset);
                    method.visitVarInsn(ILOAD, lengths.get(i));
                    method.visitInsn(IADD);
                    method.visitVarInsn(ISTORE, offset);
                }
                else {
                    method.visitVarInsn(ALOAD, result);
                    method.visitVarInsn(ILOAD, offset);
                    method.visitVarInsn(loadOpcode(element), values.get(i));
                    method.visitInsn(arrayStoreOpcode(element));
                    method.visitIincInsn(offset, 1);
                }
            }
            method.visitTypeInsn(NEW, runtime.owner);
            method.visitInsn(DUP);
            method.visitVarInsn(ALOAD, result);
            method.visitMethodInsn(
                INVOKESPECIAL,
                runtime.owner,
                "<init>",
                runtime.constructorDescriptor,
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
                method.visitTypeInsn(NEW, "java/util/ArrayList");
                method.visitInsn(DUP);
                method.visitLdcInsn(array.elements().size());
                method.visitMethodInsn(
                    INVOKESPECIAL,
                    "java/util/ArrayList",
                    "<init>",
                    "(I)V",
                    false
                );
                for (final Expression element : array.elements()) {
                    method.visitInsn(DUP);
                    expression(element);
                    box(semanticModel.getEffectiveType(element));
                    method.visitMethodInsn(
                        INVOKEVIRTUAL,
                        "java/util/ArrayList",
                        "add",
                        "(Ljava/lang/Object;)Z",
                        false
                    );
                    method.visitInsn(POP);
                }
                return;
            }
            final Type element =
                ((ArrayType) semanticModel.getExpressionType(array))
                    .elementType();
            final RuntimeAbi.ArrayKind runtime = RuntimeAbi.array(element);
            method.visitTypeInsn(NEW, runtime.owner);
            method.visitInsn(DUP);
            if (array.elements().isEmpty()) {
                method.visitMethodInsn(
                    INVOKESPECIAL,
                    runtime.owner,
                    "<init>",
                    RuntimeAbi.EMPTY_ARRAY_CONSTRUCTOR,
                    false
                );
                return;
            }
            method.visitLdcInsn(array.elements().size());
            if (runtime == RuntimeAbi.ArrayKind.OBJECT) {
                method.visitTypeInsn(ANEWARRAY, "java/lang/Object");
            }
            else {
                method.visitIntInsn(NEWARRAY, runtime.creationOpcode);
            }
            for (int i = 0; i < array.elements().size(); i++) {
                method.visitInsn(DUP);
                method.visitLdcInsn(i);
                expression(array.elements().get(i));
                method.visitInsn(arrayStoreOpcode(element));
            }
            method.visitMethodInsn(
                INVOKESPECIAL,
                runtime.owner,
                "<init>",
                runtime.constructorDescriptor,
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
            method.visitMethodInsn(
                INVOKEVIRTUAL,
                array.owner,
                operation.methodName,
                array.methodDescriptor(operation),
                false
            );
        }

        private void arrayGet(final Type element) {
            arrayCall(element, RuntimeAbi.ArrayMethod.GET);
            if (
                RuntimeAbi.array(element) == RuntimeAbi.ArrayKind.OBJECT
                    && !descriptor(element).equals("Ljava/lang/Object;")
            ) {
                method.visitTypeInsn(
                    CHECKCAST,
                    org.objectweb.asm.Type.getType(descriptor(element))
                        .getInternalName()
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
                method.visitLdcInsn(
                    new Handle(
                        isInterface ? H_INVOKEINTERFACE : H_INVOKEVIRTUAL,
                        org.objectweb.asm.Type
                            .getInternalName(javaMethod.getDeclaringClass()),
                        function.name(),
                        org.objectweb.asm.Type.getMethodDescriptor(javaMethod),
                        isInterface
                    )
                );
                expression(member.target());
                box(semanticModel.getEffectiveType(member.target()));
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle",
                    "bindTo",
                    "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;",
                    false
                );
                method.visitLdcInsn(
                    org.objectweb.asm.Type
                        .getMethodType(methodDescriptor(function.type()))
                );
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle",
                    "asType",
                    "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                    false
                );
                return;
            }
            if (symbol instanceof FunctionSymbol function) {
                method.visitLdcInsn(
                    new Handle(
                        semanticModel
                            .getMemberOwner(member) instanceof InterfaceType
                                ? H_INVOKEINTERFACE
                                : H_INVOKEVIRTUAL,
                        memberOwner(member),
                        symbol.name(),
                        methodDescriptor(function.type()),
                        semanticModel
                            .getMemberOwner(member) instanceof InterfaceType
                    )
                );
                memberReceiver(member);
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle",
                    "bindTo",
                    "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;",
                    false
                );
            }
            else if (
                semanticModel.getMemberOwner(member) instanceof InterfaceType
            ) {
                memberReceiver(member);
                method.visitMethodInsn(
                    INVOKEINTERFACE,
                    memberOwner(member),
                    "$get$" + symbol.name(),
                    "()" + descriptor(symbol.type()),
                    true
                );
            }
            else {
                memberReceiver(member);
                method.visitFieldInsn(
                    GETFIELD,
                    memberOwner(member),
                    symbol.name(),
                    descriptor(symbol.type())
                );
            }
        }

        private void assign(final AssignmentExpression assignment) {
            final Expression target = unwrap(assignment.target());
            if (target instanceof IdentifierExpression identifier) {
                final Symbol symbol = semanticModel.getReference(identifier);
                final boolean instanceField = prepareStore(symbol);
                expression(assignment.value());
                method.visitInsn(
                    slots(symbol.type()) == 2
                        ? (instanceField ? DUP2_X1 : DUP2)
                        : (instanceField ? DUP_X1 : DUP)
                );
                store(symbol);
            }
            else if (target instanceof MemberExpression member) {
                final Type type = semanticModel.getExpressionType(member);
                memberReceiver(member);
                expression(assignment.value());
                method.visitInsn(slots(type) == 2 ? DUP2_X1 : DUP_X1);
                storeMember(member, type);
            }
            else if (target instanceof SubscriptExpression index) {
                arrayIndex(index);
                expression(assignment.value());
                method.visitInsn(
                    slots(semanticModel.getExpressionType(index)) == 2
                        ? DUP2_X2
                        : DUP_X2
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
                method.visitMethodInsn(
                    INVOKEINTERFACE,
                    memberOwner(member),
                    "$set$" + name,
                    "(" + descriptor(type) + ")V",
                    true
                );
            }
            else {
                method.visitFieldInsn(
                    PUTFIELD,
                    memberOwner(member),
                    name,
                    descriptor(type)
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
                    method.visitInsn(
                        slots(symbol.type()) == 2
                            ? (instanceField ? DUP2_X1 : DUP2)
                            : (instanceField ? DUP_X1 : DUP)
                    );
                }
                addOne(type, increase);
                if (!postfix) {
                    method.visitInsn(
                        slots(symbol.type()) == 2
                            ? (instanceField ? DUP2_X1 : DUP2)
                            : (instanceField ? DUP_X1 : DUP)
                    );
                }
                store(symbol);
            }
            else if (target instanceof MemberExpression member) {
                memberReceiver(member);
                method.visitInsn(DUP);
                if (
                    semanticModel
                        .getMemberOwner(member) instanceof InterfaceType
                ) {
                    method.visitMethodInsn(
                        INVOKEINTERFACE,
                        memberOwner(member),
                        "$get$" + semanticModel.getReference(member.member())
                            .name(),
                        "()" + descriptor(type),
                        true
                    );
                }
                else {
                    method.visitFieldInsn(
                        GETFIELD,
                        memberOwner(member),
                        semanticModel.getReference(member.member()).name(),
                        descriptor(type)
                    );
                }
                if (postfix) {
                    method.visitInsn(slots(type) == 2 ? DUP2_X1 : DUP_X1);
                }
                addOne(type, increase);
                if (!postfix) {
                    method.visitInsn(slots(type) == 2 ? DUP2_X1 : DUP_X1);
                }
                storeMember(member, type);
            }
            else if (target instanceof SubscriptExpression index) {
                arrayIndex(index);
                method.visitInsn(DUP2);
                arrayGet(type);
                if (postfix) {
                    method.visitInsn(
                        slots(semanticModel.getExpressionType(index)) == 2
                            ? DUP2_X2
                            : DUP_X2
                    );
                }
                addOne(type, increase);
                if (!postfix) {
                    method.visitInsn(
                        slots(semanticModel.getExpressionType(index)) == 2
                            ? DUP2_X2
                            : DUP_X2
                    );
                }
                arraySet(type);
            }
            else {
                throw unsupported(operand, "Invalid increment target");
            }
        }

        private void addOne(final Type type, final boolean increase) {
            method.visitInsn(
                type == BuiltinType.F64
                    ? DCONST_1
                    : type == BuiltinType.I64
                        ? LCONST_1
                        : type == BuiltinType.F32 ? FCONST_1 : ICONST_1
            );
            method.visitInsn(opcode(type, increase ? IADD : ISUB));
            narrow(type);
            if (type == BuiltinType.CHAR) {
                method.visitInsn(I2C);
            }
        }

        private void binary(final BinaryExpression binary) {
            final BinaryOperator operator = binary.operator();
            if (
                operator == BinaryOperator.AND || operator == BinaryOperator.OR
            ) {
                final Label shortcut = new Label();
                final Label end = new Label();
                expression(binary.left());
                method.visitJumpInsn(
                    operator == BinaryOperator.AND ? IFEQ : IFNE,
                    shortcut
                );
                expression(binary.right());
                method.visitJumpInsn(GOTO, end);
                method.visitLabel(shortcut);
                method.visitInsn(
                    operator == BinaryOperator.AND ? ICONST_0 : ICONST_1
                );
                method.visitLabel(end);
                return;
            }
            final Type type = semanticModel.getEffectiveType(binary.left());
            expression(binary.left());
            expression(binary.right());
            if (operator == BinaryOperator.ADD && type == BuiltinType.STRING) {
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/String",
                    "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;",
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
                        method.visitMethodInsn(
                            INVOKESTATIC,
                            "java/util/Objects",
                            "equals",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Z",
                            false
                        );
                        if (operator == BinaryOperator.NOT_EQUAL) {
                            method.visitInsn(ICONST_1);
                            method.visitInsn(IXOR);
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
                    final int opcode = switch (operator) {
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
                        method.visitInsn(LCMP);
                        booleanResult(opcode - IF_ICMPEQ + IFEQ);
                    }
                    else if (
                        type == BuiltinType.F32 || type == BuiltinType.F64
                    ) {
                        // Choose the NaN result so ordered comparisons remain false.
                        method.visitInsn(
                            operator == BinaryOperator.LESS
                                || operator == BinaryOperator.LESS_EQUAL
                                    ? (type == BuiltinType.F64 ? DCMPG : FCMPG)
                                    : (type == BuiltinType.F64 ? DCMPL : FCMPL)
                        );
                        booleanResult(opcode - IF_ICMPEQ + IFEQ);
                    }
                    else {
                        booleanResult(opcode);
                    }
                }
                return;
            }
            method.visitInsn(opcode(type, switch (operator) {
                case ADD -> IADD;
                case SUBTRACT -> ISUB;
                case MULTIPLY -> IMUL;
                case DIVIDE -> IDIV;
                case MODULO -> IREM;
                default -> throw unsupported(
                    binary,
                    "Unsupported arithmetic operator"
                );
            }));
            narrow(type);
        }

        private void booleanResult(final int opcode) {
            final Label yes = new Label();
            final Label end = new Label();
            method.visitJumpInsn(opcode, yes);
            method.visitInsn(ICONST_0);
            method.visitJumpInsn(GOTO, end);
            method.visitLabel(yes);
            method.visitInsn(ICONST_1);
            method.visitLabel(end);
        }
    }
}
