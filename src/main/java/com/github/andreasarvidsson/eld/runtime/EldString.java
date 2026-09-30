package com.github.andreasarvidsson.eld.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class EldString {
    private EldString() {}

    public static int count(final String value, final String part) {
        if (part.isEmpty()) {
            return value.length() + 1;
        }
        int count = 0;
        int from = 0;
        while ((from = value.indexOf(part, from)) >= 0) {
            count++;
            from += part.length();
        }
        return count;
    }

    public static @Nullable Integer index(
        final String value,
        final String part
    ) {
        return index(value, part, 0);
    }

    public static @Nullable Integer index(
        final String value,
        final String part,
        final int from
    ) {
        final int found = value.indexOf(part, from);
        return found < 0 ? null : found;
    }

    public static @Nullable Integer lastIndex(
        final String value,
        final String part
    ) {
        return lastIndex(value, part, value.length());
    }

    public static @Nullable Integer lastIndex(
        final String value,
        final String part,
        final int from
    ) {
        final int found = value.lastIndexOf(part, from);
        return found < 0 ? null : found;
    }

    public static boolean isDigit(final String value) {
        return !value.isEmpty()
            && value.codePoints().allMatch(Character::isDigit);
    }

    public static boolean isAlnum(final String value) {
        return !value.isEmpty()
            && value.codePoints().allMatch(Character::isLetterOrDigit);
    }

    public static boolean isAlpha(final String value) {
        return !value.isEmpty()
            && value.codePoints().allMatch(Character::isLetter);
    }

    public static boolean isLower(final String value) {
        return hasCase(value)
            && value.codePoints().noneMatch(Character::isUpperCase);
    }

    public static boolean isUpper(final String value) {
        return hasCase(value)
            && value.codePoints().noneMatch(Character::isLowerCase);
    }

    private static boolean hasCase(final String value) {
        return value.codePoints()
            .anyMatch(
                point -> Character.isLowerCase(point)
                    || Character.isUpperCase(point)
            );
    }

    public static char charAt(final String value, final int index) {
        return value.charAt(normalizeIndex(value, index));
    }

    public static EldByteArray bytes(final String value) {
        return new EldByteArray(value.getBytes(StandardCharsets.UTF_8));
    }

    public static EldCharArray chars(final String value) {
        return new EldCharArray(value.toCharArray());
    }

    public static boolean matches(final String value, final String regex) {
        return Pattern.compile(regex).matcher(value).find();
    }

    public static String slice(
        final String value,
        final int start,
        final int end
    ) {
        final int from = normalizeBoundary(value, start);
        final int to = normalizeBoundary(value, end);
        return value.substring(from, Math.max(from, to));
    }

    public static String sliceFrom(final String value, final int start) {
        return value.substring(normalizeBoundary(value, start));
    }

    public static String sliceTo(final String value, final int end) {
        return value.substring(0, normalizeBoundary(value, end));
    }

    private static int normalizeIndex(final String value, final int index) {
        final int normalized = index < 0 ? value.length() + index : index;
        if (normalized < 0 || normalized >= value.length()) {
            throw new StringIndexOutOfBoundsException(index);
        }
        return normalized;
    }

    private static int normalizeBoundary(final String value, final int index) {
        return Math.clamp(
            index < 0 ? value.length() + index : index,
            0,
            value.length()
        );
    }

    public static String capitalize(final String value) {
        return value.isEmpty()
            ? value
            : value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1);
    }

    public static String title(final String value) {
        return value.isEmpty()
            ? value
            : Arrays.stream(value.split("\\s+"))
                .map(
                    word -> word.isEmpty()
                        ? word
                        : word.substring(0, 1).toUpperCase(Locale.ROOT)
                            + word.substring(1)
                )
                .collect(Collectors.joining(" "));
    }

    public static String strip(final String value, final String chars) {
        return stripEnd(stripStart(value, chars), chars);
    }

    public static String stripStart(final String value, final String chars) {
        int index = 0;
        while (
            index < value.length() && chars.indexOf(value.charAt(index)) >= 0
        ) {
            index++;
        }
        return value.substring(index);
    }

    public static String stripEnd(final String value, final String chars) {
        int index = value.length();
        while (index > 0 && chars.indexOf(value.charAt(index - 1)) >= 0) {
            index--;
        }
        return value.substring(0, index);
    }

    public static String padStart(
        final String value,
        final int length,
        final String padding
    ) {
        return pad(value, length, padding, true);
    }

    public static String padEnd(
        final String value,
        final int length,
        final String padding
    ) {
        return pad(value, length, padding, false);
    }

    private static String pad(
        final String value,
        final int length,
        final String padding,
        final boolean start
    ) {
        if (padding.isEmpty() || length <= value.length()) {
            return value;
        }
        final StringBuilder repeated =
            new StringBuilder(length - value.length());
        while (repeated.length() < length - value.length()) {
            repeated.append(padding);
        }
        repeated.setLength(length - value.length());
        return start ? repeated + value : value + repeated.toString();
    }

    public static String replace(
        final String value,
        final String target,
        final String replacement
    ) {
        final int index = value.indexOf(target);
        return index < 0
            ? value
            : value.substring(0, index) + replacement
                + value.substring(index + target.length());
    }

    public static EldObjectArray<String> lines(final String value) {
        return strings(value.lines().toList());
    }

    public static EldObjectArray<String> split(final String value) {
        final String stripped = value.strip();
        return stripped.isEmpty()
            ? strings(List.of())
            : strings(List.of(stripped.split("\\s+")));
    }

    public static EldObjectArray<String> split(
        final String value,
        final String separator
    ) {
        return split(value, separator, Integer.MAX_VALUE);
    }

    public static EldObjectArray<String> split(
        final String value,
        final String separator,
        final int maxSplits
    ) {
        if (separator.isEmpty()) {
            return splitCharacters(value, maxSplits);
        }
        final List<String> parts = new ArrayList<>();
        int from = 0;
        int splits = 0;
        int index;
        while (
            splits < maxSplits && (index = value.indexOf(separator, from)) >= 0
        ) {
            parts.add(value.substring(from, index));
            from = index + separator.length();
            splits++;
        }
        parts.add(value.substring(from));
        return strings(parts);
    }

    private static EldObjectArray<String> splitCharacters(
        final String value,
        final int maxSplits
    ) {
        if (value.isEmpty()) {
            return strings(List.of());
        }
        final List<String> parts = new ArrayList<>();
        int from = 0;
        int splits = 0;
        while (splits < maxSplits) {
            final int boundary = value.offsetByCodePoints(from, 1);
            if (boundary == value.length()) {
                break;
            }
            parts.add(value.substring(from, boundary));
            from = boundary;
            splits++;
        }
        parts.add(value.substring(from));
        return strings(parts);
    }

    private static EldObjectArray<String> strings(final List<String> values) {
        return new EldObjectArray<>(values.toArray());
    }

    public static String reverse(final String value) {
        return new StringBuilder(value).reverse().toString();
    }
}
