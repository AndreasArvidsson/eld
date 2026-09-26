package com.github.andreasarvidsson.eld.semantic;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.AssignmentExpression;
import com.github.andreasarvidsson.eld.parser.AstTraversal;
import com.github.andreasarvidsson.eld.parser.BinaryExpression;
import com.github.andreasarvidsson.eld.parser.BinaryOperator;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.Expression;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.NewExpression;
import com.github.andreasarvidsson.eld.parser.PostfixExpression;
import com.github.andreasarvidsson.eld.parser.UnaryExpression;
import com.github.andreasarvidsson.eld.parser.UnaryOperator;

final class TypeNarrowing {
    private final SemanticModel model;

    TypeNarrowing(final SemanticModel model) {
        this.model = model;
    }

    void condition(
        final Expression condition,
        final Scope scope,
        final boolean whenTrue
    ) {
        if (hasMutation(condition)) {
            return;
        }
        final Expression expression =
            SemanticAnalyzerExpressionOperations.unwrap(condition);
        if (
            expression instanceof UnaryExpression unary
                && unary.operator() == UnaryOperator.NOT
        ) {
            condition(unary.operand(), scope, !whenTrue);
            return;
        }
        if (!(expression instanceof BinaryExpression binary)) {
            return;
        }
        if (binary.operator() == BinaryOperator.INSTANCEOF) {
            if (whenTrue) {
                instanceOf(binary.left(), binary.right(), scope);
            }
            return;
        }
        if (
            (binary.operator() == BinaryOperator.AND && whenTrue)
                || (binary.operator() == BinaryOperator.OR && !whenTrue)
        ) {
            condition(binary.left(), scope, whenTrue);
            condition(binary.right(), scope, whenTrue);
            return;
        }
        if (
            binary.operator() != BinaryOperator.EQUAL
                && binary.operator() != BinaryOperator.NOT_EQUAL
        ) {
            return;
        }
        final boolean equal =
            whenTrue == (binary.operator() == BinaryOperator.EQUAL);
        comparison(binary.left(), binary.right(), scope, equal);
        comparison(binary.right(), binary.left(), scope, equal);
    }

    void comparison(
        final Expression candidate,
        final Expression match,
        final Scope scope,
        final boolean equal
    ) {
        final Expression unwrapped =
            SemanticAnalyzerExpressionOperations.unwrap(candidate);
        if (!(unwrapped instanceof IdentifierExpression identifier)) {
            return;
        }
        final var symbol = model.findReference(identifier);
        if (!(symbol instanceof VariableSymbol variable)) {
            return;
        }
        comparisonType(variable, model.getExpressionType(match), scope, equal);
    }

    private void instanceOf(
        final Expression candidate,
        final Expression match,
        final Scope scope
    ) {
        final Type matchType = ConstType.unwrap(model.getExpressionType(match));
        if (
            !(matchType instanceof InterfaceType classToken)
                || !JavaTypes.isClassType(classToken)
        ) {
            return;
        }
        narrowInstanceOf(
            candidate,
            List.of(classToken.typeArguments().getFirst()),
            scope
        );
    }

    private void narrowInstanceOf(
        final Expression candidate,
        final List<Type> targets,
        final Scope scope
    ) {
        final Expression unwrapped =
            SemanticAnalyzerExpressionOperations.unwrap(candidate);
        if (!(unwrapped instanceof IdentifierExpression identifier)) {
            return;
        }
        if (
            !(model
                .findReference(identifier) instanceof VariableSymbol variable)
        ) {
            return;
        }
        final Type original = scope.typeOf(variable);
        final List<Type> originals =
            original instanceof UnionType union
                ? union.memberTypes()
                : List.of(original);
        final List<Type> members = new ArrayList<>();
        for (final Type member : originals) {
            for (final Type target : targets) {
                if (model.isSubtype(member, target)) {
                    if (!members.contains(member)) {
                        members.add(member);
                    }
                }
                else if (
                    member == BuiltinType.ANY || model.isSubtype(target, member)
                ) {
                    if (!members.contains(target)) {
                        members.add(target);
                    }
                }
            }
        }
        if (!members.isEmpty()) {
            final Type narrowed = UnionType.of(members);
            if (!narrowed.equals(original)) {
                scope.narrow(variable, narrowed);
            }
        }
    }

    private void comparisonType(
        final VariableSymbol variable,
        final Type matchType,
        final Scope scope,
        final boolean equal
    ) {
        final Type current = scope.typeOf(variable);
        final Type narrowed = narrow(current, matchType, equal);
        if (narrowed != null && !narrowed.equals(current)) {
            scope.narrow(variable, narrowed);
        }
    }

    void match(
        final Expression subject,
        final List<Expression> matches,
        final Scope scope,
        final boolean equal
    ) {
        if (matches.stream().anyMatch(model::isSwitchDualMatch)) {
            return;
        }
        if (matches.stream().allMatch(model::isSwitchTypeMatch)) {
            if (equal) {
                narrowInstanceOf(
                    subject,
                    matches.stream()
                        .map(model::getExpressionType)
                        .map(ConstType::unwrap)
                        .map(
                            type -> ((InterfaceType) type).typeArguments()
                                .getFirst()
                        )
                        .toList(),
                    scope
                );
            }
            return;
        }
        if (matches.size() == 1) {
            comparison(subject, matches.getFirst(), scope, equal);
        }
        else if (equal) {
            final Expression candidate =
                SemanticAnalyzerExpressionOperations.unwrap(subject);
            if (
                candidate instanceof IdentifierExpression identifier
                    && model.findReference(
                        identifier
                    ) instanceof VariableSymbol variable
            ) {
                final List<Type> types =
                    matches.stream().map(model::getExpressionType).toList();
                comparisonType(variable, UnionType.of(types), scope, true);
            }
        }
        else {
            for (final Expression match : matches) {
                comparison(subject, match, scope, false);
            }
        }
    }

    private @Nullable Type narrow(
        final Type original,
        final Type matched,
        final boolean equal
    ) {
        final Type target = ConstType.unwrap(matched);
        if (!equal && target != BuiltinType.NULL) {
            return null;
        }
        final List<Type> targets =
            target instanceof UnionType matchUnion
                ? matchUnion.memberTypes()
                : List.of(target);
        final List<Type> originals =
            original instanceof UnionType union
                ? union.memberTypes()
                : List.of(original);
        final List<Type> members = new ArrayList<>();
        for (final Type member : originals) {
            if (!equal) {
                if (!model.isSubtype(member, target)) {
                    members.add(member);
                }
                continue;
            }
            for (final Type type : targets) {
                if (model.isSubtype(member, type)) {
                    if (!members.contains(member)) {
                        members.add(member);
                    }
                }
                else if (model.isSubtype(type, member)) {
                    final Type narrowed =
                        JavaTypes.isClassType(member)
                            && JavaTypes.isClassType(type) ? type : member;
                    if (!members.contains(narrowed)) {
                        members.add(narrowed);
                    }
                }
            }
        }
        if (members.isEmpty()) {
            return null;
        }
        final Type result = UnionType.of(members);
        return result.equals(original) ? null : result;
    }

    static boolean hasMutation(final Expression expression) {
        return AstTraversal.anyMatch(
            expression,
            node -> node instanceof AssignmentExpression
                || node instanceof CallExpression
                || node instanceof NewExpression
                || node instanceof PostfixExpression
                || (node instanceof UnaryExpression unary
                    && (unary.operator() == UnaryOperator.INCREMENT
                        || unary.operator() == UnaryOperator.DECREMENT))
        );
    }
}
