package mylang.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import mylang.parser.ArrayExpression;
import mylang.parser.ArrayTypeNode;
import mylang.parser.AssignmentExpression;
import mylang.parser.BinaryExpression;
import mylang.parser.BlockItem;
import mylang.parser.BlockStatement;
import mylang.parser.BreakStatement;
import mylang.parser.CallExpression;
import mylang.parser.ClassDeclaration;
import mylang.parser.ContinueStatement;
import mylang.parser.Declaration;
import mylang.parser.DeclarationStatement;
import mylang.parser.DoWhileStatement;
import mylang.parser.ElseIfBranch;
import mylang.parser.Expression;
import mylang.parser.ExpressionStatement;
import mylang.parser.ForEachStatement;
import mylang.parser.ForStatement;
import mylang.parser.FunctionDeclaration;
import mylang.parser.GroupingExpression;
import mylang.parser.IdentifierDeclaration;
import mylang.parser.IdentifierExpression;
import mylang.parser.IfStatement;
import mylang.parser.IndexExpression;
import mylang.parser.LiteralExpression;
import mylang.parser.Mutability;
import mylang.parser.NamedTypeNode;
import mylang.parser.Parameter;
import mylang.parser.PostfixExpression;
import mylang.parser.Program;
import mylang.parser.ReturnStatement;
import mylang.parser.Statement;
import mylang.parser.TypeNode;
import mylang.parser.UnaryExpression;
import mylang.parser.VariableDeclaration;
import mylang.parser.WhileStatement;

public final class SemanticAnalyzer {
    private final SemanticModel model = new SemanticModel();

    public SemanticModel analyze(final Program program) {
        final Scope globalScope = new Scope(null);
        final SemanticContext context =
            new SemanticContext(globalScope, null, 0);

        analyzeProgram(program, context);

        return model;
    }

    private void analyzeProgram(
        final Program program,
        final SemanticContext context
    ) {
        for (final @NonNull BlockItem item : program.items()) {
            analyzeBlockItem(item, context);
        }
    }

    private void analyzeBlockStatement(
        final BlockStatement block,
        final SemanticContext context
    ) {
        final SemanticContext blockContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth()
            );
        for (final BlockItem item : block.items()) {
            analyzeBlockItem(item, blockContext);
        }
    }

    private void analyzeBlockItem(
        final BlockItem item,
        final SemanticContext context
    ) {
        switch (item) {
            case Declaration declaration -> analyzeDeclaration(
                declaration,
                context
            );
            case Statement statement -> analyzeStatement(statement, context);
        }
    }

    private void analyzeDeclaration(
        final Declaration declaration,
        final SemanticContext context
    ) {
        switch (declaration) {
            case VariableDeclaration variableDeclaration -> analyzeVariableDeclaration(
                variableDeclaration,
                context
            );
            case FunctionDeclaration functionDeclaration -> analyzeFunctionDeclaration(
                functionDeclaration,
                context
            );
            case ClassDeclaration classDeclaration -> analyzeClassDeclaration(
                classDeclaration,
                context
            );
            default -> throw new SemanticException(
                declaration.range(),
                "Unsupported declaration: %s",
                declaration
            );
        }
    }

    private void analyzeStatement(
        final Statement statement,
        final SemanticContext context
    ) {
        switch (statement) {
            case DeclarationStatement declarationStatement -> analyzeDeclaration(
                declarationStatement.declaration(),
                context
            );
            case ExpressionStatement expressionStatement -> analyzeExpression(
                expressionStatement.expression(),
                context
            );
            case WhileStatement whileStatement -> analyzeWhileStatement(
                whileStatement,
                context
            );
            case DoWhileStatement doWhileStatement -> analyzeDoWhileStatement(
                doWhileStatement,
                context
            );
            case ForStatement forStatement -> analyzeForStatement(
                forStatement,
                context
            );
            case ForEachStatement forStatement -> analyzeForEachStatement(
                forStatement,
                context
            );
            case IfStatement ifStatement -> analyzeIfStatement(
                ifStatement,
                context
            );
            case ContinueStatement continueStatement -> analyzeContinueStatement(
                continueStatement,
                context
            );
            case BreakStatement breakStatement -> analyzeBreakStatement(
                breakStatement,
                context
            );
            case ReturnStatement returnStatement -> analyzeReturnStatement(
                returnStatement,
                context
            );
            case BlockStatement blockStatement -> analyzeBlockStatement(
                blockStatement,
                context
            );
            default -> throw new SemanticException(
                statement.range(),
                "Unsupported statement: %s",
                statement
            );
        }
    }

    private void analyzeClassDeclaration(
        final ClassDeclaration declaration,
        final SemanticContext context
    ) {
        final SemanticContext classContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth()
            );

        for (final var member : declaration.members()) {
            analyzeBlockItem(member, classContext);
        }

        final ClassType classType = new ClassType(declaration.name().name());
        final ClassSymbol classSymbol =
            new ClassSymbol(declaration.name(), classType);
        model.setSymbol(declaration.name(), classSymbol);
        classContext.scope().declare(classSymbol);
    }

    private void analyzeForStatement(
        final ForStatement statement,
        final SemanticContext context
    ) {
        final SemanticContext loopContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth() + 1
            );

        final Statement initializer = statement.initializer();
        final Expression condition = statement.condition();
        final Expression update = statement.update();

        if (initializer != null) {
            analyzeStatement(initializer, loopContext);
        }

        if (condition != null) {
            final Type conditionType =
                analyzeExpression(condition, loopContext);
            if (conditionType != BuiltinType.BOOL) {
                throw new SemanticException(
                    condition.range(),
                    "For condition must be bool, found %s",
                    conditionType
                );
            }
        }

        if (update != null) {
            analyzeExpression(update, loopContext);
        }

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeForEachStatement(
        final ForEachStatement statement,
        final SemanticContext context
    ) {
        final SemanticContext loopContext =
            new SemanticContext(
                new Scope(context.scope()),
                context.function(),
                context.loopDepth() + 1
            );
        final Type iterableType =
            analyzeExpression(statement.iterable(), loopContext);
        if (!(iterableType instanceof ArrayType arrayType)) {
            throw new SemanticException(
                statement.iterable().range(),
                "For-each iterable must be an array, found %s",
                iterableType
            );
        }
        final IdentifierDeclaration index = statement.index();

        final VariableSymbol valueSymbol =
            new VariableSymbol(
                statement.value(),
                arrayType.elementType(),
                Mutability.CONST
            );

        model.setSymbol(statement.value(), valueSymbol);
        loopContext.scope().declare(valueSymbol);

        if (index != null) {
            final VariableSymbol indexSymbol =
                new VariableSymbol(index, BuiltinType.INT, Mutability.CONST);
            model.setSymbol(index, indexSymbol);
            loopContext.scope().declare(indexSymbol);
        }

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeWhileStatement(
        final WhileStatement statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzeExpression(statement.condition(), context);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                statement.condition().range(),
                "While condition must be bool, found %s",
                conditionType
            );
        }

        final SemanticContext loopContext =
            new SemanticContext(
                context.scope(),
                context.function(),
                context.loopDepth() + 1
            );

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeDoWhileStatement(
        final DoWhileStatement statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzeExpression(statement.condition(), context);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                statement.condition().range(),
                "Do-while condition must be bool, found %s",
                conditionType
            );
        }

        final SemanticContext loopContext =
            new SemanticContext(
                context.scope(),
                context.function(),
                context.loopDepth() + 1
            );

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeIfStatement(
        final IfStatement statement,
        final SemanticContext context
    ) {
        final Type conditionType =
            analyzeExpression(statement.condition(), context);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                statement.condition().range(),
                "If condition must be bool, found %s",
                conditionType
            );
        }

        analyzeBlockStatement(statement.thenBranch(), context);

        for (final ElseIfBranch branch : statement.elifBranches()) {
            final Type branchConditionType =
                analyzeExpression(branch.condition(), context);

            if (branchConditionType != BuiltinType.BOOL) {
                throw new SemanticException(
                    branch.condition().range(),
                    "Else-if condition must be bool, found %s",
                    branchConditionType
                );
            }

            analyzeBlockStatement(branch.branch(), context);
        }

        final @Nullable Statement elseBranch = statement.elseBranch();

        if (elseBranch != null) {
            analyzeStatement(elseBranch, context);
        }
    }

    private void analyzeContinueStatement(
        final ContinueStatement statement,
        final SemanticContext context
    ) {
        if (!context.isWithinLoop()) {
            throw new SemanticException(
                statement.range(),
                "A 'continue' statement can only be used within an enclosing loop"
            );
        }
    }

    private void analyzeBreakStatement(
        final BreakStatement statement,
        final SemanticContext context
    ) {
        if (!context.isWithinLoop()) {
            throw new SemanticException(
                statement.range(),
                "A 'break' statement can only be used within an enclosing loop"
            );
        }
    }

    private void analyzeReturnStatement(
        final ReturnStatement statement,
        final SemanticContext context
    ) {
        final FunctionSymbol function = context.function();

        if (function == null) {
            throw new SemanticException(
                statement.range(),
                "A 'return' statement can only be used within an enclosing function"
            );
        }

        final @Nullable Expression value = statement.value();
        final Type returnType = function.type().returnType();

        if (value == null) {
            if (returnType.equals(BuiltinType.VOID)) {
                return;
            }
            throw new SemanticException(
                statement.range(),
                "Return statement must return a value of type %s",
                returnType
            );
        }

        final Type valueType = analyzeExpression(value, context);
        final @Nullable Type resolvedType =
            resolveAssignType(valueType, returnType, value);

        if (resolvedType == null) {
            throw new SemanticException(
                statement.range(),
                "Type mismatch: cannot return %s from function with return type %s",
                valueType,
                returnType
            );
        }
    }

    private void analyzeVariableDeclaration(
        final VariableDeclaration declaration,
        final SemanticContext context
    ) {
        final TypeNode typeNode = declaration.type();
        final Expression initializer = declaration.initializer();
        final Type declaredType =
            typeNode != null ? resolveType(typeNode) : null;
        Type initializerType =
            initializer != null
                ? analyzeExpression(initializer, context)
                : null;

        if (declaredType == null && initializerType == null) {
            throw new SemanticException(
                declaration.name().range(),
                "Cannot infer type for variable '%s' without an initializer",
                declaration.name().name()
            );
        }

        if (initializerType == BuiltinType.VOID) {
            throw new SemanticException(
                declaration.range(),
                "A variable initializer must produce a value"
            );
        }

        if (
            declaredType != null && initializer != null
                && initializerType != null
        ) {
            final @Nullable Type resolvedInitializerType =
                resolveAssignType(initializerType, declaredType, initializer);
            if (resolvedInitializerType == null) {
                throw new SemanticException(
                    initializer.range(),
                    "Type mismatch: cannot assign %s to %s",
                    initializerType,
                    declaredType
                );
            }
            initializerType = resolvedInitializerType;
        }

        final VariableSymbol symbol =
            new VariableSymbol(
                declaration.name(),
                declaredType != null
                    ? declaredType
                    : Objects.requireNonNull(initializerType),
                declaration.mutability()
            );

        context.scope().declare(symbol);
        model.setSymbol(declaration.name(), symbol);
    }

    private void analyzeFunctionDeclaration(
        final FunctionDeclaration declaration,
        final SemanticContext context
    ) {
        // TODO: Verify that the parent is program or class body

        final List<@NonNull Type> parameterTypes = new ArrayList<>();
        final Scope functionScope = new Scope(context.scope());

        for (final Parameter param : declaration.parameters()) {
            final TypeNode typeNode = Objects.requireNonNull(param.type());
            final Type paramType = resolveType(typeNode);
            final VariableSymbol paramSymbol =
                new VariableSymbol(param.name(), paramType, Mutability.CONST);
            model.setResolvedType(typeNode, paramType);
            model.setSymbol(param.name(), paramSymbol);
            functionScope.declare(paramSymbol);
            parameterTypes.add(paramType);
        }

        final Type returnType =
            declaration.returnType() != null
                ? resolveType(Objects.requireNonNull(declaration.returnType()))
                : BuiltinType.VOID;

        final FunctionSymbol symbol =
            new FunctionSymbol(
                declaration.name(),
                new FunctionType(
                    Objects.requireNonNull(parameterTypes),
                    returnType
                )
            );

        context.scope().declare(symbol);
        model.setSymbol(declaration.name(), symbol);

        final SemanticContext functionContext =
            new SemanticContext(functionScope, symbol, 0);

        analyzeBlockStatement(declaration.body(), functionContext);
    }

    private Type resolveType(final TypeNode typeNode) {
        return switch (typeNode) {
            case NamedTypeNode named -> resolveNamedType(named);
            case ArrayTypeNode array -> {
                final Type type =
                    new ArrayType(resolveType(array.elementType()));
                model.setResolvedType(array, type);
                yield type;
            }
            default -> throw new SemanticException(
                typeNode.range(),
                "Unsupported type: %s",
                typeNode
            );
        };
    }

    private @Nullable Type resolveAssignType(
        final Type from,
        final Type to,
        final Expression fromExpression
    ) {

        // TODO: Extend this to handle more complex type assignability rules, such as
        // subtyping and type coercion.

        if (from.equals(to)) {
            return from;
        }

        if (from.equals(BuiltinType.INT) && to.equals(BuiltinType.FLOAT)) {
            model.setConversionType(fromExpression, to);
            return to;
        }

        return null;
    }

    private Type resolveNamedType(final NamedTypeNode named) {
        final Type type = switch (named.name()) {
            case "int" -> BuiltinType.INT;
            case "float" -> BuiltinType.FLOAT;
            case "char" -> BuiltinType.CHAR;
            case "boolean" -> BuiltinType.BOOL;
            case "string" -> BuiltinType.STRING;
            case "null" -> BuiltinType.NULL;
            default -> throw new SemanticException(
                named.range(),
                "Unknown type: %s",
                named.name()
            );
        };

        model.setResolvedType(named, type);

        return type;
    }

    private Type analyzeExpression(
        final Expression expression,
        final SemanticContext context
    ) {

        final Type type = switch (expression) {
            case LiteralExpression literal -> analyzeLiteralExpression(literal);
            case IdentifierExpression identifier -> analyzeIdentifierExpression(
                identifier,
                context
            );
            case ArrayExpression array -> analyzeArrayExpression(
                array,
                context
            );
            case BinaryExpression binary -> analyzeBinaryExpression(
                binary,
                context
            );
            case UnaryExpression unary -> analyzeUnaryExpression(
                unary,
                context
            );
            case PostfixExpression postfix -> analyzePostfixExpression(
                postfix,
                context
            );
            case CallExpression call -> analyzeCallExpression(call, context);
            case GroupingExpression grouping -> analyzeExpression(
                grouping.expression(),
                context
            );
            case IndexExpression index -> analyzeIndexExpression(
                index,
                context
            );
            case AssignmentExpression assignment -> analyzeAssignmentExpression(
                assignment,
                context
            );
            // TODO: Implement lambda expression analysis
            // case LambdaExpression lambda ->
            // analyzeLambdaExpression(lambda, context);
            default -> throw new SemanticException(
                expression.range(),
                "Unsupported expression: %s",
                expression
            );
        };

        model.setExpressionType(expression, type);

        return type;
    }

    private Type analyzeCallExpression(
        final CallExpression call,
        final SemanticContext context
    ) {
        final Type type = analyzeExpression(call.callee(), context);
        if (!(type instanceof FunctionType function)) {
            throw new SemanticException(
                call.callee().range(),
                "Expression is not callable: %s",
                type
            );
        }
        if (call.arguments().size() != function.parameterTypes().size()) {
            throw new SemanticException(
                call.range(),
                "Expected %s arguments, found %s",
                function.parameterTypes().size(),
                call.arguments().size()
            );
        }
        for (int i = 0; i < call.arguments().size(); i++) {
            final Expression argument = call.arguments().get(i);
            final Type actual = analyzeExpression(argument, context);
            final Type expected = function.parameterTypes().get(i);
            if (resolveAssignType(actual, expected, argument) == null) {
                throw new SemanticException(
                    argument.range(),
                    "Cannot pass %s as %s",
                    actual,
                    expected
                );
            }
        }
        return function.returnType();
    }

    private Type analyzeIndexExpression(
        final IndexExpression index,
        final SemanticContext context
    ) {
        final Type target = analyzeExpression(index.target(), context);
        final Type subscript = analyzeExpression(index.index(), context);
        if (
            !(target instanceof ArrayType array) || subscript != BuiltinType.INT
        ) {
            throw new SemanticException(
                index.range(),
                "Indexing requires an array and an int index"
            );
        }
        return array.elementType();
    }

    private Type analyzeAssignmentExpression(
        final AssignmentExpression assignment,
        final SemanticContext context
    ) {
        final Type target = analyzeExpression(assignment.target(), context);
        requireWritable(assignment.target());
        final Type value = analyzeExpression(assignment.value(), context);
        if (resolveAssignType(value, target, assignment.value()) == null) {
            throw new SemanticException(
                assignment.range(),
                "Cannot assign %s to %s",
                value,
                target
            );
        }
        return target;
    }

    private void requireWritable(final Expression expression) {
        if (expression instanceof GroupingExpression grouping) {
            requireWritable(grouping.expression());
            return;
        }
        if (expression instanceof IndexExpression) {
            return;
        }
        if (
            expression instanceof IdentifierExpression identifier
                && model
                    .getReference(identifier) instanceof VariableSymbol variable
                && variable.mutability() == Mutability.VAR
        ) {
            return;
        }
        throw new SemanticException(
            expression.range(),
            "Expression is not writable"
        );
    }

    private Type analyzePostfixExpression(
        final PostfixExpression postfix,
        final SemanticContext context
    ) {
        final Type type = analyzeExpression(postfix.operand(), context);
        requireWritable(postfix.operand());

        if (!numeric(type)) {
            throw new SemanticException(
                postfix.range(),
                "Increment requires a numeric operand"
            );
        }

        model.setExpressionType(postfix, type);
        return type;
    }

    private Type analyzeBinaryExpression(
        final BinaryExpression binary,
        final SemanticContext context
    ) {
        final Type leftType = analyzeExpression(binary.left(), context);
        final Type rightType = analyzeExpression(binary.right(), context);
        Type resolvedType = leftType;
        final boolean compatibleNumbers =
            numeric(leftType) && numeric(rightType)
                && (leftType.equals(rightType)
                    || (leftType == BuiltinType.INT
                        && rightType == BuiltinType.FLOAT)
                    || (leftType == BuiltinType.FLOAT
                        && rightType == BuiltinType.INT));
        final boolean valid = switch (binary.operator()) {
            case AND, OR -> leftType == BuiltinType.BOOL
                && rightType == BuiltinType.BOOL;
            case EQUAL, NOT_EQUAL -> compatibleNumbers
                || (leftType.equals(rightType) && leftType != BuiltinType.VOID);
            case ADD -> compatibleNumbers || (leftType == BuiltinType.STRING
                && rightType == BuiltinType.STRING);
            default -> compatibleNumbers;
        };
        if (!valid) {
            throw new SemanticException(
                binary.range(),
                "Operator %s is not applicable to %s and %s",
                binary.operator(),
                leftType,
                rightType
            );
        }

        // TODO: Extend this to handle more complex type assignability rules, such as
        // subtyping and type coercion.

        if (
            leftType.equals(BuiltinType.FLOAT)
                && rightType.equals(BuiltinType.INT)
        ) {
            model.setConversionType(binary.right(), leftType);
            resolvedType = leftType;
        }
        else if (
            leftType.equals(BuiltinType.INT)
                && rightType.equals(BuiltinType.FLOAT)
        ) {
            model.setConversionType(binary.left(), rightType);
            resolvedType = rightType;
        }

        final Type resultType =
            binary.operator().isBool() ? BuiltinType.BOOL : resolvedType;
        model.setExpressionType(binary, resultType);
        return resultType;
    }

    private Type analyzeUnaryExpression(
        final UnaryExpression unary,
        final SemanticContext context
    ) {
        final Type operandType = analyzeExpression(unary.operand(), context);
        switch (unary.operator()) {
            case INCREMENT, DECREMENT -> {
                requireWritable(unary.operand());
                if (!numeric(operandType)) {
                    throw new SemanticException(
                        unary.range(),
                        "Increment requires a numeric operand"
                    );
                }
            }
            case NOT -> {
                if (operandType != BuiltinType.BOOL) {
                    throw new SemanticException(
                        unary.range(),
                        "Logical negation requires bool"
                    );
                }
            }
            case PLUS, MINUS -> {
                if (!numeric(operandType)) {
                    throw new SemanticException(
                        unary.range(),
                        "Unary arithmetic requires a numeric operand"
                    );
                }
            }
        }

        final Type resultType = operandType;
        model.setExpressionType(unary, resultType);
        return resultType;
    }

    private Type analyzeIdentifierExpression(
        final IdentifierExpression identifier,
        final SemanticContext context
    ) {
        final @Nullable Symbol symbol =
            context.scope().resolve(identifier.name());

        if (symbol == null) {
            throw new SemanticException(
                identifier.range(),
                "Undefined identifier: '%s'",
                identifier.name()
            );
        }

        final Type type = symbol.type();
        model.setExpressionType(identifier, type);
        model.setReference(identifier, symbol);
        return type;
    }

    private static boolean numeric(final Type type) {
        return type == BuiltinType.INT || type == BuiltinType.FLOAT
            || type == BuiltinType.CHAR;
    }

    private Type analyzeLiteralExpression(final LiteralExpression literal) {
        final Type type = switch (literal.kind()) {
            case INT -> BuiltinType.INT;
            case FLOAT -> BuiltinType.FLOAT;
            case CHAR -> BuiltinType.CHAR;
            case BOOL -> BuiltinType.BOOL;
            case STRING -> BuiltinType.STRING;
            case NULL -> BuiltinType.NULL;
        };

        model.setExpressionType(literal, type);

        return type;
    }

    private ArrayType analyzeArrayExpression(
        final ArrayExpression array,
        final SemanticContext context
    ) {
        final List<@NonNull Type> elementTypes = new ArrayList<>();

        for (final Expression element : array.elements()) {
            elementTypes.add(analyzeExpression(element, context));
        }

        // For simplicity, we assume all elements must have the same type
        final Type elementType =
            elementTypes.isEmpty()
                ? BuiltinType.NULL
                : Objects.requireNonNull(elementTypes.get(0));

        if (elementType == BuiltinType.VOID) {
            throw new SemanticException(
                array.range(),
                "Array elements must produce values"
            );
        }

        for (final Type type : elementTypes) {
            if (!type.equals(elementType)) {
                throw new SemanticException(
                    array.range(),
                    "Array elements must have the same type"
                );
            }
        }

        final ArrayType type = new ArrayType(elementType);
        model.setExpressionType(array, type);
        return type;
    }
}
