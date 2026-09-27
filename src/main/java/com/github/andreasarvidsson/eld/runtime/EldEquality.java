package com.github.andreasarvidsson.eld.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class EldEquality {
    private EldEquality() {}

    /** Distinguishes generated Eld classes from Java types at runtime. */
    public interface EldObject {
    }

    @SuppressWarnings("ReferenceEquality")
    public static boolean identityEquals(
        final @Nullable Object left,
        final @Nullable Object right
    ) {
        return left == right;
    }

    @SuppressWarnings("ReferenceEquality")
    public static boolean referenceEquals(
        final @Nullable Object left,
        final @Nullable Object right
    ) {
        if (left instanceof Record) {
            return left.equals(right);
        }
        if (right instanceof Record) {
            return right.equals(left);
        }
        return (left instanceof EldObject || right instanceof EldObject)
            ? left == right
            : Objects.equals(left, right);
    }

    public static boolean dynamicEquals(
        final @Nullable Object left,
        final @Nullable Object right
    ) {
        if (
            left instanceof Number leftNumber
                && right instanceof Number rightNumber
        ) {
            if (
                leftNumber instanceof Float || leftNumber instanceof Double
                    || rightNumber instanceof Float
                    || rightNumber instanceof Double
            ) {
                return leftNumber.doubleValue() == rightNumber.doubleValue();
            }
            return leftNumber.longValue() == rightNumber.longValue();
        }
        if (
            left instanceof String leftString
                && right instanceof String rightString
        ) {
            return leftString.equals(rightString);
        }
        if (
            left instanceof Boolean leftBool
                && right instanceof Boolean rightBool
        ) {
            return leftBool.booleanValue() == rightBool.booleanValue();
        }
        if (
            left instanceof Character leftChar
                && right instanceof Character rightChar
        ) {
            return leftChar.charValue() == rightChar.charValue();
        }
        if (left instanceof Record || right instanceof Record) {
            return referenceEquals(left, right);
        }
        if (left instanceof EldObject && right instanceof EldObject) {
            for (Class<?> common = left.getClass(); common != null; common =
                common.getSuperclass()) {
                if (!common.isAssignableFrom(right.getClass())) {
                    continue;
                }
                final Method equal;
                try {
                    equal = common.getDeclaredMethod("equal", common, common);
                }
                catch (final NoSuchMethodException ignored) {
                    continue;
                }
                if (
                    !Modifier.isStatic(equal.getModifiers())
                        || equal.getReturnType() != boolean.class
                ) {
                    continue;
                }
                try {
                    equal.setAccessible(true);
                    return (boolean) equal.invoke(null, left, right);
                }
                catch (final InvocationTargetException e) {
                    final Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new RuntimeException(cause);
                }
                catch (final IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return referenceEquals(left, right);
    }
}
