package com.github.andreasarvidsson.eld;

import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
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
import com.github.andreasarvidsson.eld.semantic.FunctionSymbol;
import com.github.andreasarvidsson.eld.semantic.FunctionType;
import com.github.andreasarvidsson.eld.semantic.SemanticModel;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzer;
import com.github.andreasarvidsson.eld.semantic.Symbol;
import com.github.andreasarvidsson.eld.semantic.Type;
import com.github.andreasarvidsson.eld.semantic.UnionType;

/** Generates a Java 21 module named Test and its declared classes. */
public final class BytecodeGenerator {
    private final String moduleName;
    private final String parentName;
    private final int previousItems;
    private final Program program;
    private final SemanticModel semanticModel;
    private final Map<String, String> classOwners;

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
                .anyMatch(ClassDeclaration.class::isInstance)
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
     * source order in the public no-argument constructor; const fields are final.
     */
    public Map<String, byte[]> generateClasses() {
        final Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(moduleName, generateModule());
        for (final BlockItem item : program.items()
            .subList(previousItems, program.items().size())) {
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
                // Generated classes extend Object and are not available to ASM's class loader.
                if (left.equals(right)) {
                    return left;
                }
                if (
                    left.startsWith(moduleName + "$")
                        || right.startsWith(moduleName + "$")
                        || classOwners.containsValue(left)
                        || classOwners.containsValue(right)
                ) {
                    return "java/lang/Object";
                }
                return super.getCommonSuperClass(left, right);
            }
        };
    }

    private String className(final ClassDeclaration declaration) {
        return moduleName + "$" + declaration.name().name();
    }

    private byte[] generateClass(final ClassDeclaration declaration) {
        final String name = className(declaration);
        final ClassWriter writer = classWriter();
        writer.visit(
            V21,
            ACC_PUBLIC | ACC_SUPER,
            name,
            null,
            "java/lang/Object",
            null
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
        for (final BlockItem member : declaration.members()) {
            if (member instanceof VariableDeclaration variable) {
                final Symbol symbol = semanticModel.getSymbol(variable.name());
                members.put(symbol, symbol.name());
                // Instance constants must be assigned by each constructor.
                writer
                    .visitField(
                        ACC_PUBLIC | (variable.mutability() == Mutability.CONST
                            ? ACC_FINAL
                            : 0),
                        symbol.name(),
                        descriptor(symbol.type()),
                        null,
                        null
                    )
                    .visitEnd();
            }
            else if (member instanceof FunctionDeclaration function) {
                final Symbol symbol = semanticModel.getSymbol(function.name());
                members.put(symbol, symbol.name());
            }
            else if (member instanceof ClassDeclaration) {
                throw unsupported(
                    member,
                    "Nested class generation is not supported yet"
                );
            }
        }
        final InstanceContext instance = new InstanceContext(name, members);
        final MethodVisitor constructor =
            writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        final MethodGenerator initializer =
            new MethodGenerator(
                constructor,
                globals,
                BuiltinType.VOID,
                instance
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
        boolean reachable = true;
        for (final BlockItem member : declaration.members()) {
            if (reachable && !(member instanceof FunctionDeclaration)) {
                reachable = initializer.item(member);
            }
        }
        initializer.finish(reachable);
        for (final BlockItem member : declaration.members()) {
            if (member instanceof FunctionDeclaration function) {
                generateFunction(writer, function, globals, instance);
            }
        }
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
                ACC_PUBLIC | (instance == null ? ACC_STATIC : 0),
                symbol.name(),
                methodDescriptor(symbol.type()),
                null,
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
        for (final Parameter parameter : function.parameters()) {
            generator.local(semanticModel.getSymbol(parameter.name()));
        }
        generator.finish(generator.block(function.body()));
    }

    private byte[] generateModule() {
        final ClassWriter writer = classWriter();
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
                        null,
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
        // The lexer recognizes escaped quotes and backslashes only.
        final StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (
                c == '\\' && i + 1 < text.length() - 1
                    && (text.charAt(i + 1) == '\\' || text.charAt(i + 1) == '"')
            ) {
                c = text.charAt(++i);
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    private String classOwner(final ClassType type) {
        return classOwners
            .getOrDefault(type.name(), moduleName + "$" + type.name());
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
            this.nextLocal = instance == null ? 0 : 1;
        }

        private int local(final Symbol symbol) {
            return locals.computeIfAbsent(symbol, ignored -> {
                final int slot = nextLocal;
                nextLocal += slots(symbol.type());
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

        private boolean item(final BlockItem item) {
            switch (item) {
                case VariableDeclaration variable -> {
                    final Symbol symbol =
                        semanticModel.getSymbol(variable.name());
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
                    final Label condition = new Label();
                    final Label end = new Label();
                    method.visitLabel(condition);
                    expression(statement.condition());
                    method.visitJumpInsn(IFEQ, end);
                    if (loopBody(statement.body(), new Loop(condition, end))) {
                        method.visitJumpInsn(GOTO, condition);
                    }
                    method.visitLabel(end);
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
                        expression(statement.condition());
                        method.visitJumpInsn(IFNE, start);
                    }
                    method.visitLabel(end);
                    return reachesCondition || loop.hasBreak;
                }
                case ForStatement statement -> {
                    final Statement initializer = statement.initializer();
                    final Expression condition = statement.condition();
                    final Expression update = statement.update();
                    if (initializer != null) {
                        item(initializer);
                    }
                    final Label start = new Label();
                    final Label next = new Label();
                    final Label end = new Label();
                    method.visitLabel(start);
                    if (condition != null) {
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
                    return condition != null || loop.hasBreak;
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
                method.visitVarInsn(loadOpcode(symbol.type()), slot);
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
            if (
                expression instanceof UnaryExpression unary
                    && SemanticAnalyzer.integerLiteral(unary) != null
            ) {
                if (
                    semanticModel
                        .getEffectiveType(expression) instanceof UnionType
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
                case NewExpression creation -> {
                    final String name =
                        ((ClassType) semanticModel.getExpressionType(creation))
                            .name();
                    final String owner =
                        classOwners.getOrDefault(name, moduleName + "$" + name);
                    method.visitTypeInsn(NEW, owner);
                    method.visitInsn(DUP);
                    method.visitMethodInsn(
                        INVOKESPECIAL,
                        owner,
                        "<init>",
                        "()V",
                        false
                    );
                }
                case NamedArgumentExpression named ->
                    throw unsupported(named, "Named argument outside a call");
                case TupleExpression tuple -> tuple(tuple);
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
                case LambdaExpression lambda -> throw unsupported(
                    lambda,
                    "Lambda generation requires closure analysis"
                );
            }
            convertExpression(expression);
        }

        private void convertExpression(final Expression expression) {
            final Type from = semanticModel.getExpressionType(expression);
            final Type to = semanticModel.getEffectiveType(expression);
            if (to == BuiltinType.ANY) {
                box(from);
            }
            else if (to instanceof UnionType) {
                final Type member =
                    semanticModel.getUnionMemberType(expression);
                if (!(from instanceof UnionType)) {
                    convert(from, member);
                    box(member);
                }
                final String target = descriptor(to);
                if (
                    !target.equals("Ljava/lang/Object;")
                        && !boxedDescriptor(member).equals(target)
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
            }
        }

        private void call(final CallExpression call) {
            final Type calleeType =
                semanticModel.getExpressionType(call.callee());
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
                    ) instanceof FunctionSymbol function
            ) {
                memberReceiver(member);
                callArguments(call);
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    memberOwner(member),
                    function.name(),
                    methodDescriptor(function.type()),
                    false
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
                    methodDescriptor(type),
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

        private void callArguments(final CallExpression call) {
            if (
                call.arguments()
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
            final FunctionType type =
                (FunctionType) semanticModel.getExpressionType(call.callee());
            final List<Integer> parameters =
                semanticModel.getArgumentParameters(call);
            final int[] locals = new int[parameters.size()];
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
                expression(value);
                method.visitVarInsn(storeOpcode(parameterType), slot);
            }
            for (int i = 0; i < locals.length; i++) {
                method.visitVarInsn(
                    loadOpcode(type.parameterTypes().get(i)),
                    locals[i]
                );
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

        private void array(final ArrayExpression array) {
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

        private String memberOwner(final MemberExpression member) {
            return classOwner(semanticModel.getMemberOwner(member));
        }

        private void memberReceiver(final MemberExpression member) {
            expression(member.target());
        }

        private void member(final MemberExpression member) {
            final Symbol symbol = semanticModel.getReference(member.member());
            if (symbol instanceof FunctionSymbol function) {
                method.visitLdcInsn(
                    new Handle(
                        H_INVOKEVIRTUAL,
                        memberOwner(member),
                        symbol.name(),
                        methodDescriptor(function.type()),
                        false
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
                method.visitFieldInsn(
                    PUTFIELD,
                    memberOwner(member),
                    semanticModel.getReference(member.member()).name(),
                    descriptor(type)
                );
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
                method.visitFieldInsn(
                    GETFIELD,
                    memberOwner(member),
                    semanticModel.getReference(member.member()).name(),
                    descriptor(type)
                );
                if (postfix) {
                    method.visitInsn(slots(type) == 2 ? DUP2_X1 : DUP_X1);
                }
                addOne(type, increase);
                if (!postfix) {
                    method.visitInsn(slots(type) == 2 ? DUP2_X1 : DUP_X1);
                }
                method.visitFieldInsn(
                    PUTFIELD,
                    memberOwner(member),
                    semanticModel.getReference(member.member()).name(),
                    descriptor(type)
                );
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
