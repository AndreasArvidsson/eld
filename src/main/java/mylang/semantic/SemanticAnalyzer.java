package mylang.semantic;

import java.util.Objects;

import org.jspecify.annotations.NonNull;

import mylang.parser.BlockItem;
import mylang.parser.Declaration;
import mylang.parser.Statement;
import mylang.parser.TypeNode;
import mylang.parser.VariableDeclaration;
import mylang.parser.Expression;
import mylang.parser.LiteralExpression;
import mylang.parser.NamedTypeNode;
import mylang.parser.Program;

public final class SemanticAnalyzer {
    private final SemanticModel model = new SemanticModel();

    public SemanticModel analyze(final Program program) {
        final Scope globalScope = new Scope(null);

        analyzeProgram(program, globalScope);

        return model;
    }

    private void analyzeProgram(
            final Program program,
            final Scope scope) {
        for (final @NonNull BlockItem item : program.items()) {
            analyzeBlockItem(item, scope);
        }
    }

    private void analyzeBlockItem(
            final BlockItem item,
            final Scope scope) {
        switch (item) {
            case Declaration declaration ->
                analyzeDeclaration(declaration, scope);
            case Statement statement ->
                analyzeStatement(statement, scope);
        }
    }

    private void analyzeDeclaration(
            final Declaration declaration,
            final Scope scope) {
        switch (declaration) {
            case VariableDeclaration variableDeclaration ->
                analyzeVariable(variableDeclaration, scope);
            default -> throw new SemanticException(
                    declaration.range(),
                    "Unsupported declaration: %s",
                    declaration);
        }
    }

    private Type analyzeStatement(
            final Statement statement,
            final Scope scope) {
        throw new SemanticException(
                statement.range(),
                "Unsupported statement: %s",
                statement);
    }

    private void analyzeVariable(final VariableDeclaration declaration, final Scope scope) {
        final TypeNode typeNode = declaration.type();
        final Expression initializer = declaration.initializer();
        final Type declaredType = typeNode != null ? resolveType(typeNode) : null;
        final Type initializerType = initializer != null ? analyzeExpression(initializer, scope) : null;

        if (declaredType == null && initializerType == null) {
            throw new SemanticException(
                    declaration.range(),
                    "Cannot infer type for variable '%s' without an initializer",
                    declaration.name());
        }

        if (declaredType != null && initializerType != null && !isAssignable(initializerType, declaredType)) {
            throw new SemanticException(
                    Objects.requireNonNull(initializer).range(),
                    "Type mismatch: cannot assign %s to %s",
                    initializerType,
                    declaredType);
        }

        final VariableSymbol symbol = new VariableSymbol(
                declaration.name().name(),
                declaredType != null ? declaredType : Objects.requireNonNull(initializerType),
                declaration.mutability());

        scope.declare(symbol);
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
            case "boolean" -> BuiltinType.BOOLEAN;
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
            final Scope scope) {

        final Type type = switch (expression) {
            case LiteralExpression literal -> analyzeLiteral(literal);

            // case IdentifierExpression identifier ->
            // analyzeIdentifier(identifier, scope);

            // case BinaryExpression binary ->
            // analyzeBinary(binary, scope);

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

    private Type analyzeLiteral(final LiteralExpression literal) {
        Type type = switch (literal.kind()) {
            case INTEGER -> BuiltinType.INT;
            case FLOAT -> BuiltinType.FLOAT;
            case CHAR -> BuiltinType.CHAR;
            case BOOLEAN -> BuiltinType.BOOLEAN;
            case STRING -> BuiltinType.STRING;
            case NULL -> BuiltinType.NULL;
        };

        model.setExpressionType(literal, type);

        return type;
    }
}
