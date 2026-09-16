package mylang;

import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import mylang.parser.*;
import mylang.semantic.ArrayType;
import mylang.semantic.BuiltinFunctionSymbol;
import mylang.semantic.BuiltinType;
import mylang.semantic.ClassType;
import mylang.semantic.FunctionSymbol;
import mylang.semantic.FunctionType;
import mylang.semantic.SemanticModel;
import mylang.semantic.Symbol;
import mylang.semantic.Type;

/** Generates a Java 21 module named Test and its declared classes. */
public final class BytecodeGenerator {
    private static final String CLASS_NAME = "Test";
    private final Program program;
    private final SemanticModel semanticModel;

    public BytecodeGenerator(
        final Program program,
        final SemanticModel semanticModel
    ) {
        this.program = program;
        this.semanticModel = semanticModel;
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
        classes.put(CLASS_NAME, generateModule());
        for (final BlockItem item : program.items()) {
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

    private static String className(final ClassDeclaration declaration) {
        return CLASS_NAME + "$" + declaration.name().name();
    }

    private byte[] generateClass(final ClassDeclaration declaration) {
        final String name = className(declaration);
        final ClassWriter writer =
            new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
            );
        writer.visit(
            V21,
            ACC_PUBLIC | ACC_SUPER,
            name,
            null,
            "java/lang/Object",
            null
        );
        writer.visitNestHost(CLASS_NAME);
        writer.visitInnerClass(
            name,
            CLASS_NAME,
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
        final ClassWriter writer =
            new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
            );
        writer.visit(
            V21,
            ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
            CLASS_NAME,
            null,
            "java/lang/Object",
            null
        );
        final IdentityHashMap<Symbol, String> globals = new IdentityHashMap<>();
        final List<BlockItem> initializers = new ArrayList<>();
        for (final BlockItem item : program.items()) {
            if (item instanceof VariableDeclaration variable) {
                final Symbol symbol = semanticModel.getSymbol(variable.name());
                final Expression initializer = variable.initializer();
                final Object constantValue =
                    variable.mutability() == Mutability.CONST
                        && initializer != null
                            ? constantValue(initializer)
                            : null;
                if (initializer != null && constantValue == null) {
                    initializers.add(item);
                }
                globals.put(symbol, symbol.name());
                writer
                    .visitField(
                        ACC_PUBLIC | ACC_STATIC
                            | (variable.mutability() == Mutability.CONST
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
                    CLASS_NAME,
                    declaration.name().name(),
                    ACC_PUBLIC | ACC_STATIC
                );
            }
            else if (!(item instanceof FunctionDeclaration)) {
                initializers.add(item);
            }
        }
        for (final BlockItem item : program.items()) {
            if (item instanceof FunctionDeclaration function) {
                generateFunction(writer, function, globals, null);
            }
        }
        if (!initializers.isEmpty()) {
            final MethodVisitor method =
                writer.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            final MethodGenerator generator =
                new MethodGenerator(method, globals, BuiltinType.VOID);
            method.visitCode();
            boolean reachable = true;
            for (final BlockItem item : initializers) {
                if (!reachable) {
                    break;
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
        final Object value = switch (expression) {
            case LiteralExpression literal -> switch (literal.kind()) {
                case INT -> Integer.parseInt(literal.text().replace("_", ""));
                case FLOAT -> Float.parseFloat(literal.text().replace("_", ""));
                case BOOL -> Boolean.parseBoolean(literal.text()) ? 1 : 0;
                case CHAR -> (int) literal.text().charAt(1);
                case STRING -> decodeString(literal.text());
                case NULL -> null;
            };
            case GroupingExpression grouping ->
                constantValue(grouping.expression());
            case UnaryExpression unary -> constantUnary(unary);
            case BinaryExpression binary -> constantBinary(binary);
            default -> null;
        };
        if (
            value instanceof Integer integer && semanticModel
                .getEffectiveType(expression) == BuiltinType.FLOAT
        ) {
            return integer.floatValue();
        }
        return value;
    }

    private @Nullable Object constantUnary(final UnaryExpression unary) {
        if (
            unary.operator() == UnaryOperator.MINUS
                && unary.operand() instanceof LiteralExpression literal
                && literal.kind() == LiteralKind.INT
                && literal.text().replace("_", "").equals("2147483648")
        ) {
            return Integer.MIN_VALUE;
        }
        final Object operand = constantValue(unary.operand());
        return switch (unary.operator()) {
            case PLUS -> operand;
            case MINUS -> {
                if (operand instanceof Integer integer) {
                    yield -integer;
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

    private static String descriptor(final Type type) {
        return switch (type) {
            case BuiltinType builtin -> switch (builtin) {
                case INT -> "I";
                case FLOAT -> "F";
                case BOOL -> "Z";
                case CHAR -> "C";
                case STRING -> "Ljava/lang/String;";
                case NULL -> "Ljava/lang/Object;";
                case VOID -> "V";
            };
            case ArrayType array -> "[" + descriptor(array.elementType());
            case FunctionType ignored -> "Ljava/lang/invoke/MethodHandle;";
            case ClassType ignored -> "Ljava/lang/Object;";
        };
    }

    private static String methodDescriptor(final FunctionType type) {
        final StringBuilder result = new StringBuilder("(");
        type.parameterTypes()
            .forEach(parameter -> result.append(descriptor(parameter)));
        return result.append(')')
            .append(descriptor(type.returnType()))
            .toString();
    }

    private static boolean reference(final Type type) {
        return type instanceof ArrayType || type instanceof FunctionType
            || type == BuiltinType.STRING
            || type == BuiltinType.NULL;
    }

    private static int loadOpcode(final Type type) {
        return reference(type)
            ? ALOAD
            : type == BuiltinType.FLOAT ? FLOAD : ILOAD;
    }

    private static int storeOpcode(final Type type) {
        return reference(type)
            ? ASTORE
            : type == BuiltinType.FLOAT ? FSTORE : ISTORE;
    }

    private static int returnOpcode(final Type type) {
        return type == BuiltinType.VOID
            ? RETURN
            : reference(type)
                ? ARETURN
                : type == BuiltinType.FLOAT ? FRETURN : IRETURN;
    }

    private static int arrayLoadOpcode(final Type type) {
        return reference(type)
            ? AALOAD
            : type == BuiltinType.FLOAT
                ? FALOAD
                : type == BuiltinType.BOOL
                    ? BALOAD
                    : type == BuiltinType.CHAR ? CALOAD : IALOAD;
    }

    private static int arrayStoreOpcode(final Type type) {
        return reference(type)
            ? AASTORE
            : type == BuiltinType.FLOAT
                ? FASTORE
                : type == BuiltinType.BOOL
                    ? BASTORE
                    : type == BuiltinType.CHAR ? CASTORE : IASTORE;
    }

    private static IllegalArgumentException unsupported(
        final AstNode node,
        final String message
    ) {
        return new IllegalArgumentException(message + " at " + node.range());
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
            return locals.computeIfAbsent(symbol, ignored -> nextLocal++);
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
                    if (initializer == null) {
                        method.visitInsn(
                            reference(symbol.type())
                                ? ACONST_NULL
                                : symbol.type() == BuiltinType.FLOAT
                                    ? FCONST_0
                                    : ICONST_0
                        );
                    }
                    else {
                        expression(initializer);
                    }
                    store(symbol);
                }
                case DeclarationStatement declaration -> {
                    return item(declaration.declaration());
                }
                case BlockStatement block -> {
                    return block(block);
                }
                case ExpressionStatement statement ->
                    discard(statement.expression());
                case ReturnStatement statement -> {
                    final Expression value = statement.value();
                    if (value != null) {
                        expression(value);
                    }
                    method.visitInsn(returnOpcode(returnType));
                    return false;
                }
                case IfStatement statement -> {
                    final Label end = new Label();
                    boolean reachable =
                        branch(
                            statement.condition(),
                            statement.thenBranch(),
                            end
                        );
                    for (final ElseIfBranch branch : statement.elifBranches()) {
                        reachable |=
                            branch(branch.condition(), branch.branch(), end);
                    }
                    final BlockStatement otherwise = statement.elseBranch();
                    if (otherwise != null) {
                        reachable |= block(otherwise);
                    }
                    method.visitLabel(end);
                    return otherwise == null || reachable;
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
            method.visitInsn(ARRAYLENGTH);
            method.visitJumpInsn(IF_ICMPGE, end);
            method.visitVarInsn(ALOAD, array);
            method.visitVarInsn(ILOAD, index);
            method.visitInsn(arrayLoadOpcode(value.type()));
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
                method.visitLdcInsn(
                    new Handle(
                        H_INVOKEVIRTUAL,
                        "java/io/PrintStream",
                        "println",
                        "(Ljava/lang/String;)V",
                        false
                    )
                );
                method.visitFieldInsn(
                    GETSTATIC,
                    "java/lang/System",
                    "out",
                    "Ljava/io/PrintStream;"
                );
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle",
                    "bindTo",
                    "(Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;",
                    false
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
                            : CLASS_NAME,
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
                    CLASS_NAME,
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
                    CLASS_NAME,
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
                method.visitInsn(POP);
            }
        }

        private void expression(final Expression expression) {
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
                            if (
                                unary
                                    .operand() instanceof LiteralExpression literal
                                    && literal.kind() == LiteralKind.INT
                                    && literal.text()
                                        .replace("_", "")
                                        .equals("2147483648")
                            ) {
                                method.visitLdcInsn(Integer.MIN_VALUE);
                            }
                            else {
                                expression(unary.operand());
                                method.visitInsn(
                                    semanticModel.getExpressionType(
                                        unary
                                    ) == BuiltinType.FLOAT ? FNEG : INEG
                                );
                            }
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
                case AssignmentExpression assignment -> assign(assignment);
                case CallExpression call -> call(call);
                case ArrayExpression array -> array(array);
                case IndexExpression index -> {
                    expression(index.target());
                    expression(index.index());
                    method.visitInsn(
                        arrayLoadOpcode(semanticModel.getExpressionType(index))
                    );
                }
                case LambdaExpression lambda -> throw unsupported(
                    lambda,
                    "Lambda generation requires closure analysis"
                );
            }
            if (
                semanticModel.getExpressionType(expression) == BuiltinType.INT
                    && semanticModel
                        .getEffectiveType(expression) == BuiltinType.FLOAT
            ) {
                method.visitInsn(I2F);
            }
        }

        private void literal(final LiteralExpression literal) {
            final String text = literal.text();
            switch (literal.kind()) {
                case INT -> method
                    .visitLdcInsn(Integer.parseInt(text.replace("_", "")));
                case FLOAT -> method
                    .visitLdcInsn(Float.parseFloat(text.replace("_", "")));
                case BOOL -> method.visitInsn(
                    Boolean.parseBoolean(text) ? ICONST_1 : ICONST_0
                );
                case NULL -> method.visitInsn(ACONST_NULL);
                case CHAR -> method.visitLdcInsn((int) text.charAt(1));
                case STRING -> method.visitLdcInsn(decodeString(text));
            }
        }

        private void call(final CallExpression call) {
            final FunctionType type =
                (FunctionType) semanticModel.getExpressionType(call.callee());
            final Expression callee = unwrap(call.callee());
            if (
                callee instanceof IdentifierExpression identifier
                    && BuiltinFunctionSymbol.PRINT.equals(
                        semanticModel.getReference(identifier)
                    )
            ) {
                method.visitFieldInsn(
                    GETSTATIC,
                    "java/lang/System",
                    "out",
                    "Ljava/io/PrintStream;"
                );
                for (final Expression argument : call.arguments()) {
                    expression(argument);
                }
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/io/PrintStream",
                    "println",
                    "(Ljava/lang/String;)V",
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
                for (final Expression argument : call.arguments()) {
                    expression(argument);
                }
                method.visitMethodInsn(
                    instanceMethod ? INVOKEVIRTUAL : INVOKESTATIC,
                    instanceMethod
                        ? Objects.requireNonNull(instance).owner()
                        : CLASS_NAME,
                    function.name(),
                    methodDescriptor(type),
                    false
                );
            }
            else {
                expression(call.callee());
                for (final Expression argument : call.arguments()) {
                    expression(argument);
                }
                method.visitMethodInsn(
                    INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandle",
                    "invokeExact",
                    methodDescriptor(type),
                    false
                );
            }
        }

        private void array(final ArrayExpression array) {
            final Type element =
                ((ArrayType) semanticModel.getExpressionType(array))
                    .elementType();
            method.visitLdcInsn(array.elements().size());
            if (reference(element)) {
                final String desc = descriptor(element);
                method.visitTypeInsn(
                    ANEWARRAY,
                    desc.startsWith("[")
                        ? desc
                        : desc.substring(1, desc.length() - 1)
                );
            }
            else {
                method.visitIntInsn(
                    NEWARRAY,
                    element == BuiltinType.FLOAT
                        ? T_FLOAT
                        : element == BuiltinType.BOOL
                            ? T_BOOLEAN
                            : element == BuiltinType.CHAR ? T_CHAR : T_INT
                );
            }
            for (int i = 0; i < array.elements().size(); i++) {
                method.visitInsn(DUP);
                method.visitLdcInsn(i);
                expression(array.elements().get(i));
                method.visitInsn(arrayStoreOpcode(element));
            }
        }

        private Expression unwrap(final Expression expression) {
            return expression instanceof GroupingExpression grouping
                ? unwrap(grouping.expression())
                : expression;
        }

        private void assign(final AssignmentExpression assignment) {
            final Expression target = unwrap(assignment.target());
            if (target instanceof IdentifierExpression identifier) {
                final Symbol symbol = semanticModel.getReference(identifier);
                final boolean instanceField = prepareStore(symbol);
                expression(assignment.value());
                method.visitInsn(instanceField ? DUP_X1 : DUP);
                store(symbol);
            }
            else if (target instanceof IndexExpression index) {
                expression(index.target());
                expression(index.index());
                expression(assignment.value());
                method.visitInsn(DUP_X2);
                method.visitInsn(
                    arrayStoreOpcode(semanticModel.getExpressionType(index))
                );
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
                    method.visitInsn(instanceField ? DUP_X1 : DUP);
                }
                addOne(type, increase);
                if (!postfix) {
                    method.visitInsn(instanceField ? DUP_X1 : DUP);
                }
                store(symbol);
            }
            else if (target instanceof IndexExpression index) {
                expression(index.target());
                expression(index.index());
                method.visitInsn(DUP2);
                method.visitInsn(arrayLoadOpcode(type));
                if (postfix) {
                    method.visitInsn(DUP_X2);
                }
                addOne(type, increase);
                if (!postfix) {
                    method.visitInsn(DUP_X2);
                }
                method.visitInsn(arrayStoreOpcode(type));
            }
            else {
                throw unsupported(operand, "Invalid increment target");
            }
        }

        private void addOne(final Type type, final boolean increase) {
            final boolean floating = type == BuiltinType.FLOAT;
            method.visitInsn(floating ? FCONST_1 : ICONST_1);
            method.visitInsn(
                floating ? increase ? FADD : FSUB : increase ? IADD : ISUB
            );
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
                    if (type == BuiltinType.STRING) {
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
                    if (type == BuiltinType.FLOAT) {
                        // Choose the NaN result so ordered comparisons remain false.
                        method.visitInsn(
                            operator == BinaryOperator.LESS
                                || operator == BinaryOperator.LESS_EQUAL
                                    ? FCMPG
                                    : FCMPL
                        );
                        booleanResult(opcode - IF_ICMPEQ + IFEQ);
                    }
                    else {
                        booleanResult(opcode);
                    }
                }
                return;
            }
            final boolean floating = type == BuiltinType.FLOAT;
            method.visitInsn(switch (operator) {
                case ADD -> floating ? FADD : IADD;
                case SUBTRACT -> floating ? FSUB : ISUB;
                case MULTIPLY -> floating ? FMUL : IMUL;
                case DIVIDE -> floating ? FDIV : IDIV;
                case MODULO -> floating ? FREM : IREM;
                default -> throw unsupported(
                    binary,
                    "Unsupported arithmetic operator"
                );
            });
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
