package com.github.andreasarvidsson.eld.semantic;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import com.github.andreasarvidsson.eld.parser.FunctionParameter;

/** Checks whether optional parameters allow two functions to accept the same signature. */
public final class FunctionSignatures {
    private FunctionSignatures() {}

    public static String formatParameterType(
        final Type type,
        final FunctionParameter parameter,
        final boolean showDefaults
    ) {
        return type.toString();
    }

    public static boolean isSubtype(
        final Type source,
        final Type target,
        final SemanticModel model
    ) {
        if (source.equals(target)) {
            return true;
        }
        if (source instanceof UnionType union) {
            return union.memberTypes()
                .stream()
                .allMatch(member -> isSubtype(member, target, model));
        }
        if (target instanceof UnionType union) {
            return union.memberTypes()
                .stream()
                .anyMatch(member -> isSubtype(source, member, model));
        }
        return model.isSubtype(source, target)
            || (target == BuiltinType.ANY && source != BuiltinType.VOID)
            || (source instanceof BuiltinType number
                && target instanceof BuiltinType wider
                && number.canWidenTo(wider));
    }

    public static boolean overlap(
        final FunctionType left,
        final List<FunctionParameter> leftParameters,
        final FunctionType right,
        final List<FunctionParameter> rightParameters,
        final Function<Type, ?> key
    ) {
        if (
            leftParameters.size() != left.parameterTypes().size()
                || rightParameters.size() != right.parameterTypes().size()
        ) {
            return false;
        }
        for (int positional = 0; positional <= Math
            .min(leftParameters.size(), rightParameters.size()); positional++) {
            boolean shared =
                leftParameters.subList(positional, leftParameters.size())
                    .stream()
                    .allMatch(FunctionParameter::omittable)
                    && rightParameters
                        .subList(positional, rightParameters.size())
                        .stream()
                        .allMatch(FunctionParameter::omittable);
            for (int i = 0; i < positional; i++) {
                if (
                    leftParameters.get(i).namedOnly()
                        || rightParameters.get(i).namedOnly()
                        || !Objects.equals(
                            key.apply(left.parameterTypes().get(i)),
                            key.apply(right.parameterTypes().get(i))
                        )
                ) {
                    shared = false;
                    break;
                }
            }
            if (shared) {
                return true;
            }
        }
        return false;
    }

    /** Finds a shared call for which neither overload is more specific. */
    public static boolean ambiguous(
        final FunctionType left,
        final List<FunctionParameter> leftParameters,
        final FunctionType right,
        final List<FunctionParameter> rightParameters,
        final BiPredicate<Type, Type> isSubtype
    ) {
        return ambiguousNamed(
            left,
            leftParameters,
            right,
            rightParameters,
            isSubtype
        );
    }

    /** Finds a shared named-argument call that neither overload wins. */
    private static boolean ambiguousNamed(
        final FunctionType left,
        final List<FunctionParameter> leftParameters,
        final FunctionType right,
        final List<FunctionParameter> rightParameters,
        final BiPredicate<Type, Type> isSubtype
    ) {
        if (
            leftParameters.size() != left.parameterTypes().size()
                || rightParameters.size() != right.parameterTypes().size()
        ) {
            return false;
        }
        for (int positional = 0; positional <= Math
            .min(leftParameters.size(), rightParameters.size()); positional++) {
            if (
                ambiguousNamed(
                    left,
                    leftParameters,
                    right,
                    rightParameters,
                    isSubtype,
                    positional
                )
            ) {
                return true;
            }
        }
        return false;
    }

    private static boolean ambiguousNamed(
        final FunctionType left,
        final List<FunctionParameter> leftParameters,
        final FunctionType right,
        final List<FunctionParameter> rightParameters,
        final BiPredicate<Type, Type> isSubtype,
        final int positional
    ) {
        int positionalState = 0;
        for (int i = 0; i < positional; i++) {
            if (
                leftParameters.get(i).namedOnly()
                    || rightParameters.get(i).namedOnly()
            ) {
                return false;
            }
            final Type leftType = left.parameterTypes().get(i);
            final Type rightType = right.parameterTypes().get(i);
            if (!sharesValue(leftType, rightType, isSubtype)) {
                return false;
            }
            if (!isSubtype.test(leftType, rightType)) {
                positionalState |= 2;
            }
            if (!isSubtype.test(rightType, leftType)) {
                positionalState |= 1;
            }
        }
        final List<@Nullable String> leftNames = left.parameterNames();
        final List<@Nullable String> rightNames = right.parameterNames();
        if (
            leftNames.size() != leftParameters.size()
                || rightNames.size() != rightParameters.size()
        ) {
            return false;
        }
        for (int i = positional; i < leftNames.size(); i++) {
            if (
                leftNames.get(i) == null && !leftParameters.get(i).omittable()
            ) {
                return false;
            }
        }
        for (int i = positional; i < rightNames.size(); i++) {
            if (
                rightNames.get(i) == null && !rightParameters.get(i).omittable()
            ) {
                return false;
            }
        }
        final Set<String> names = new LinkedHashSet<>();
        for (int i = positional; i < leftNames.size(); i++) {
            final @Nullable String label = leftNames.get(i);
            if (label != null) {
                names.add(label);
            }
        }
        for (int i = positional; i < rightNames.size(); i++) {
            final @Nullable String label = rightNames.get(i);
            if (label != null) {
                names.add(label);
            }
        }
        final boolean[] reachable = new boolean[4];
        reachable[positionalState] = true;
        for (final String name : names) {
            final int leftIndex = parameterIndex(leftNames, name, positional);
            final int rightIndex = parameterIndex(rightNames, name, positional);
            if (leftIndex < 0) {
                if (!rightParameters.get(rightIndex).omittable()) {
                    return false;
                }
                continue;
            }
            if (rightIndex < 0) {
                if (!leftParameters.get(leftIndex).omittable()) {
                    return false;
                }
                continue;
            }
            final Type leftType = left.parameterTypes().get(leftIndex);
            final Type rightType = right.parameterTypes().get(rightIndex);
            final boolean mayOmit =
                leftParameters.get(leftIndex).omittable()
                    && rightParameters.get(rightIndex).omittable();
            final boolean maySupply =
                sharesValue(leftType, rightType, isSubtype);
            if (!mayOmit && !maySupply) {
                return false;
            }
            final boolean[] next = new boolean[4];
            for (int state = 0; state < reachable.length; state++) {
                if (!reachable[state]) {
                    continue;
                }
                if (mayOmit) {
                    next[state] = true;
                }
                if (maySupply) {
                    int supplied = state;
                    if (!isSubtype.test(leftType, rightType)) {
                        supplied |= 2;
                    }
                    if (!isSubtype.test(rightType, leftType)) {
                        supplied |= 1;
                    }
                    next[supplied] = true;
                }
            }
            System.arraycopy(next, 0, reachable, 0, reachable.length);
        }
        return reachable[0] || reachable[3];
    }

    private static int parameterIndex(
        final List<@Nullable String> labels,
        final String name,
        final int start
    ) {
        for (int i = start; i < labels.size(); i++) {
            if (name.equals(labels.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sharesValue(
        final Type left,
        final Type right,
        final BiPredicate<Type, Type> isSubtype
    ) {
        final List<Type> leftMembers =
            left instanceof UnionType union
                ? union.memberTypes()
                : List.of(left);
        final List<Type> rightMembers =
            right instanceof UnionType union
                ? union.memberTypes()
                : List.of(right);
        for (final Type leftMember : leftMembers) {
            for (final Type rightMember : rightMembers) {
                if (
                    isSubtype.test(leftMember, rightMember)
                        || isSubtype.test(rightMember, leftMember)
                ) {
                    return true;
                }
            }
        }
        return false;
    }
}
