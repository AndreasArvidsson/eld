package mylang.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import mylang.parser.ArrayExpression;
import mylang.parser.BinaryExpression;
import mylang.parser.BlockItem;
import mylang.parser.BlockStatement;
import mylang.parser.BreakStatement;
import mylang.parser.ContinueStatement;
import mylang.parser.Declaration;
import mylang.parser.DeclarationStatement;
import mylang.parser.DoWhileStatement;
import mylang.parser.Expression;
import mylang.parser.ForEachStatement;
import mylang.parser.ForStatement;
import mylang.parser.IdentifierDeclaration;
import mylang.parser.IdentifierExpression;
import mylang.parser.LiteralExpression;
import mylang.parser.Mutability;
import mylang.parser.NamedTypeNode;
import mylang.parser.PostfixExpression;
import mylang.parser.Program;
import mylang.parser.ReturnStatement;
import mylang.parser.Statement;
import mylang.parser.TypeNode;
import mylang.parser.VariableDeclaration;
import mylang.parser.WhileStatement;

public final class SemanticAnalyzer {
    private final SemanticModel model = new SemanticModel();

    public SemanticModel analyze(final Program program) {
        final Scope globalScope = new Scope(null);
        final SemanticContext context = new SemanticContext(globalScope, null, 0);

        analyzeProgram(program, context);

        return model;
    }

    private void analyzeProgram(final Program program, final SemanticContext context) {
        for (final @NonNull BlockItem item : program.items()) {
            analyzeBlockItem(item, context);
        }
    }

    private void analyzeBlockItem(final BlockItem item, final SemanticContext context) {
        switch (item) {
            case Declaration declaration ->
                analyzeDeclaration(declaration, context);
            case Statement statement ->
                analyzeStatement(statement, context);
        }
    }

    private void analyzeDeclaration(final Declaration declaration, final SemanticContext context) {
        switch (declaration) {
            case VariableDeclaration variableDeclaration ->
                analyzeVariableDeclaration(variableDeclaration, context);
            default -> throw new SemanticException(
                    declaration.range(),
                    "Unsupported declaration: %s",
                    declaration);
        }
    }

    private void analyzeStatement(final Statement statement, final SemanticContext context) {
        switch (statement) {
            case WhileStatement whileStatement ->
                analyzeWhileStatement(whileStatement, context);
            case DoWhileStatement doWhileStatement ->
                analyzeDoWhileStatement(doWhileStatement, context);
            case ForStatement forStatement ->
                analyzeForStatement(forStatement, context);
            case ForEachStatement forStatement ->
                analyzeForEachStatement(forStatement, context);
            case ContinueStatement continueStatement ->
                analyzeContinueStatement(continueStatement, context);
            case BreakStatement breakStatement ->
                analyzeBreakStatement(breakStatement, context);
            case ReturnStatement returnStatement ->
                analyzeReturnStatement(returnStatement, context);
            case DeclarationStatement declarationStatement ->
                analyzeDeclaration(declarationStatement.declaration(), context);
            default -> throw new SemanticException(
                    statement.range(),
                    "Unsupported statement: %s",
                    statement);
        }
    }

    private void analyzeForStatement(final ForStatement statement, final SemanticContext context) {
        final SemanticContext loopContext = new SemanticContext(
                context.scope(),
                context.function(),
                context.loopDepth() + 1);

        final Statement initializer = statement.initializer();
        final Expression condition = statement.condition();
        final Expression update = statement.update();

        if (initializer != null) {
            analyzeStatement(initializer, loopContext);
        }

        if (condition != null) {
            final Type conditionType = analyzeExpression(condition, loopContext);
            if (conditionType != BuiltinType.BOOL) {
                throw new SemanticException(
                        "For condition must be bool, found %s",
                        conditionType,
                        condition.range());
            }
        }

        if (update != null) {
            analyzeExpression(update, loopContext);
        }

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeForEachStatement(final ForEachStatement statement, final SemanticContext context) {
        final SemanticContext loopContext = new SemanticContext(
                context.scope(),
                context.function(),
                context.loopDepth() + 1);
        final Type iterableType = analyzeExpression(statement.iterable(), loopContext);
        final ArrayType arrayType = (ArrayType) iterableType;
        final IdentifierDeclaration index = statement.index();

        // TODO: Check if the value is iterable
        // if (!(iterableType instanceof ArrayType arrayType)) {
        // throw new SemanticException(
        // "For-each iterable must be an array, found %s",
        // iterableType,
        // statement.iterable().range());
        // }

        final VariableSymbol valueSymbol = new VariableSymbol(
                statement.value(),
                arrayType.elementType(),
                Mutability.CONST);

        model.setSymbol(statement.value(), valueSymbol);
        context.scope().declare(valueSymbol);

        if (index != null) {
            final VariableSymbol indexSymbol = new VariableSymbol(
                    index,
                    BuiltinType.INT,
                    Mutability.CONST);
            model.setSymbol(index, indexSymbol);
            context.scope().declare(indexSymbol);
        }

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeWhileStatement(final WhileStatement statement, final SemanticContext context) {
        final SemanticContext loopContext = new SemanticContext(
                context.scope(),
                context.function(),
                context.loopDepth() + 1);
        final Type conditionType = analyzeExpression(statement.condition(), loopContext);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                    "While condition must be bool, found %s",
                    conditionType,
                    statement.condition().range());
        }

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeDoWhileStatement(final DoWhileStatement statement, final SemanticContext context) {
        final SemanticContext loopContext = new SemanticContext(
                context.scope(),
                context.function(),
                context.loopDepth() + 1);
        final Type conditionType = analyzeExpression(statement.condition(), loopContext);

        if (conditionType != BuiltinType.BOOL) {
            throw new SemanticException(
                    "Do-while condition must be bool, found %s",
                    conditionType,
                    statement.condition().range());
        }

        analyzeBlockStatement(statement.body(), loopContext);
    }

    private void analyzeContinueStatement(final ContinueStatement statement, final SemanticContext context) {
        if (!context.isWithinLoop()) {
            throw new SemanticException(
                    statement.range(),
                    "A 'continue' statement can only be used within an enclosing loop");
        }
    }

    private void analyzeBreakStatement(final BreakStatement statement, final SemanticContext context) {
        if (!context.isWithinLoop()) {
            throw new SemanticException(
                    statement.range(),
                    "A 'break' statement can only be used within an enclosing loop");
        }
    }

    private void analyzeReturnStatement(final ReturnStatement statement, final SemanticContext context) {
        if (!context.isWithinFunction()) {
            throw new SemanticException(
                    statement.range(),
                    "A 'return' statement can only be used within an enclosing function");
        }
    }

    private void analyzeBlockStatement(final BlockStatement block, final SemanticContext context) {
        for (final BlockItem item : block.items()) {
            analyzeBlockItem(item, context);
        }
    }

    private void analyzeVariableDeclaration(final VariableDeclaration declaration, final SemanticContext context) {
        final TypeNode typeNode = declaration.type();
        final Expression initializer = declaration.initializer();
        final Type declaredType = typeNode != null ? resolveType(typeNode) : null;
        final Type initializerType = initializer != null ? analyzeExpression(initializer, context) : null;

        if (declaredType == null && initializerType == null) {
            throw new SemanticException(
                    declaration.name().range(),
                    "Cannot infer type for variable '%s' without an initializer",
                    declaration.name().name());
        }

        if (declaredType != null && initializerType != null && !isAssignable(initializerType, declaredType)) {
            throw new SemanticException(
                    Objects.requireNonNull(initializer).range(),
                    "Type mismatch: cannot assign %s to %s",
                    initializerType,
                    declaredType);
        }

        final VariableSymbol symbol = new VariableSymbol(
                declaration.name(),
                declaredType != null ? declaredType : Objects.requireNonNull(initializerType),
                declaration.mutability());

        context.scope().declare(symbol);
        model.setSymbol(declaration.name(), symbol);
    }

    private boolean isAssignable(final Type from, final Type to) {
        // Implementation for type assignability check goes here
        // For simplicity, we assume exact type match is required
        return from.equals(to);
    }

    private Type resolveType(final TypeNode typeNode) {
        return switch (typeNode) {
            case NamedTypeNode named ->
                resolveNamedType(named);
            default -> throw new SemanticException(
                    typeNode.range(),
                    "Unsupported type: %s",
                    typeNode);
        };
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
                    named.name());
        };

        model.setResolvedType(named, type);

        return type;
    }

    private Type analyzeExpression(
            final Expression expression,
            final SemanticContext context) {

        final Type type = switch (expression) {
            case LiteralExpression literal ->
                analyzeLiteralExpression(literal);
            case IdentifierExpression identifier ->
                analyzeIdentifierExpression(identifier, context);
            case ArrayExpression array ->
                analyzeArrayExpression(array, context);
            case BinaryExpression binary ->
                analyzeBinaryExpression(binary, context);
            case PostfixExpression postfix ->
                analyzePostfixExpression(postfix, context);

            // case UnaryExpression unary ->
            // analyzeUnary(unary, scope);

            // case CallExpression call ->
            // analyzeCall(call, scope);

            // case LambdaExpression lambda ->
            // analyzeLambda(lambda, scope);

            default ->
                throw new SemanticException(expression.range(), "Unsupported expression: %s", expression);
        };

        model.setExpressionType(expression, type);

        return type;
    }

    private Type analyzePostfixExpression(final PostfixExpression postfix, final SemanticContext context) {
        final Type type = analyzeExpression(postfix.operand(), context);

        // TODO: Verify that the postfix operator is applicable to the operand type

        model.setExpressionType(postfix, type);
        return type;
    }

    private Type analyzeBinaryExpression(final BinaryExpression binary, final SemanticContext context) {
        final Type leftType = analyzeExpression(binary.left(), context);
        final Type rightType = analyzeExpression(binary.right(), context);

        if (!isAssignable(rightType, leftType)) {
            throw new SemanticException(binary.range(), "Type mismatch in binary expression");
        }

        // TODO: Verify that the binary operator and operands work together

        final Type resultType = binary.operator().isBool() ? BuiltinType.BOOL : leftType;
        model.setExpressionType(binary, resultType);
        return resultType;
    }

    private Type analyzeIdentifierExpression(final IdentifierExpression identifier, final SemanticContext context) {
        final @Nullable Symbol symbol = context.scope().resolve(identifier.name());

        if (symbol == null) {
            throw new SemanticException(identifier.range(), "Undefined identifier: %s", identifier.name());
        }

        final Type type = symbol.type();
        model.setExpressionType(identifier, type);
        model.setReference(identifier, symbol);
        return type;
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

    private ArrayType analyzeArrayExpression(final ArrayExpression array, final SemanticContext context) {
        final List<@NonNull Type> elementTypes = new ArrayList<>();

        for (final Expression element : array.elements()) {
            elementTypes.add(analyzeExpression(element, context));
        }

        // For simplicity, we assume all elements must have the same type
        final Type elementType = elementTypes.isEmpty() ? BuiltinType.NULL
                : Objects.requireNonNull(elementTypes.get(0));

        for (final Type type : elementTypes) {
            if (!isAssignable(type, elementType)) {
                throw new SemanticException(array.range(), "Array elements must have the same type");
            }
        }

        final ArrayType type = new ArrayType(elementType);
        model.setExpressionType(array, type);
        return type;
    }
}
