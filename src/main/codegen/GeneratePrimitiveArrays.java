import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Generates the primitive-backed array classes before Maven compiles Eld. */
public final class GeneratePrimitiveArrays {
    private record PrimitiveType(
        String eldName, String javaName, String boxedName, String className,
        String descriptor, String hashExpression, boolean sortable
    ) {
    }

    private static final List<PrimitiveType> TYPES =
        List.of(
            new PrimitiveType(
                "i8",
                "byte",
                "Byte",
                "EldByteArray",
                "B",
                "elements[i]",
                true
            ),
            new PrimitiveType(
                "i16",
                "short",
                "Short",
                "EldShortArray",
                "S",
                "elements[i]",
                true
            ),
            new PrimitiveType(
                "i32",
                "int",
                "Integer",
                "EldIntArray",
                "I",
                "elements[i]",
                true
            ),
            new PrimitiveType(
                "i64",
                "long",
                "Long",
                "EldLongArray",
                "J",
                "Long.hashCode(elements[i])",
                true
            ),
            new PrimitiveType(
                "f32",
                "float",
                "Float",
                "EldFloatArray",
                "F",
                "Float.hashCode(elements[i])",
                true
            ),
            new PrimitiveType(
                "f64",
                "double",
                "Double",
                "EldDoubleArray",
                "D",
                "Double.hashCode(elements[i])",
                true
            ),
            new PrimitiveType(
                "bool",
                "boolean",
                "Boolean",
                "EldBooleanArray",
                "Z",
                "Boolean.hashCode(elements[i])",
                false
            ),
            new PrimitiveType(
                "char",
                "char",
                "Character",
                "EldCharArray",
                "C",
                "elements[i]",
                true
            )
        );

    private GeneratePrimitiveArrays() {}

    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected project directory");
        }
        final Path project = Path.of(args[0]);
        final Path runtimeDirectory =
            project.resolve(
                "src/main/java/com/github/andreasarvidsson/eld/runtime"
            );
        final String primitiveTemplate =
            Files.readString(
                project
                    .resolve("src/main/codegen/EldPrimitiveArray.java.template")
            );
        final String objectTemplate =
            Files.readString(
                project.resolve("src/main/codegen/EldObjectArray.java.template")
            );
        final String methodsTemplate =
            Files.readString(
                project.resolve(
                    "src/main/codegen/EldArrayStandardMethods.java.template"
                )
            );
        final String naturalSortTemplate =
            Files.readString(
                project.resolve(
                    "src/main/codegen/EldArrayNaturalSort.java.template"
                )
            );
        final String primitiveComparatorTemplate =
            Files.readString(
                project.resolve(
                    "src/main/codegen/EldPrimitiveArrayComparator.java.template"
                )
            );
        final String primitiveMapTemplate =
            Files.readString(
                project.resolve(
                    "src/main/codegen/EldPrimitiveArrayMap.java.template"
                )
            );
        final String primitiveReduceTemplate =
            Files.readString(
                project.resolve(
                    "src/main/codegen/EldPrimitiveArrayReduce.java.template"
                )
            );

        for (final PrimitiveType type : TYPES) {
            final Path outputFile =
                runtimeDirectory.resolve(type.className() + ".java");
            Files.deleteIfExists(outputFile);
            final String source =
                getSource(
                    primitiveTemplate,
                    methodsTemplate,
                    naturalSortTemplate,
                    primitiveComparatorTemplate,
                    primitiveMapTemplate,
                    primitiveReduceTemplate,
                    type
                );
            Files.writeString(outputFile, source);
        }

        Files.writeString(
            runtimeDirectory.resolve("EldObjectArray.java"),
            objectTemplate.replace(
                "${STANDARD_METHODS}",
                standardMethods(
                    methodsTemplate,
                    "EldObjectArray<T>",
                    "@Nullable Object",
                    "T",
                    naturalSortTemplate.replace("${SELF}", "EldObjectArray<T>")
                        .stripTrailing(),
                    "Arrays.fill(result.elements, count, result.length, null);",
                    "sortObjectArray(comparator);",
                    objectComparatorHelper(),
                    objectMapBody(),
                    objectReduceBody()
                )
            )
        );
    }

    private static String getSource(
        final String template,
        final String methodsTemplate,
        final String naturalSortTemplate,
        final String primitiveComparatorTemplate,
        final String primitiveMapTemplate,
        final String primitiveReduceTemplate,
        final PrimitiveType type
    ) {
        return template.replace("${CLASS}", type.className())
            .replace("${PRIMITIVE}", type.javaName())
            .replace("${HASH_EXPRESSION}", type.hashExpression())
            .replace(
                "${STANDARD_METHODS}",
                standardMethods(
                    methodsTemplate,
                    type.className(),
                    type.javaName(),
                    type.javaName(),
                    type.sortable()
                        ? naturalSortTemplate
                            .replace("${SELF}", type.className())
                            .stripTrailing()
                        : "",
                    "",
                    "sortWithComparator(Arrays.copyOf(elements, length), 0, length, comparator);",
                    primitiveComparatorTemplate
                        .replace("${PRIMITIVE}", type.javaName())
                        .stripTrailing(),
                    primitiveMapTemplate.stripTrailing(),
                    primitiveReduceTemplate.stripTrailing()
                )
            );
    }

    private static String standardMethods(
        final String template,
        final String selfType,
        final String elementType,
        final String valueType,
        final String naturalSortMethods,
        final String filterCleanup,
        final String comparatorSortBody,
        final String comparatorSortHelper,
        final String mapBody,
        final String reduceBody
    ) {
        return template.replace("${SELF}", selfType)
            .replace("${ELEMENT_TYPE}", elementType)
            .replace("${VALUE_TYPE}", valueType)
            .replace("${NATURAL_SORT_METHODS}", naturalSortMethods)
            .replace(
                "${FILTER_CLEANUP}",
                filterCleanup.isEmpty() ? "" : "        " + filterCleanup
            )
            .replace("${COMPARATOR_SORT_BODY}", comparatorSortBody)
            .replace("${COMPARATOR_SORT_HELPER}", comparatorSortHelper)
            .replace("${MAP_BODY}", mapBody)
            .replace("${REDUCE_BODY}", reduceBody)
            .stripTrailing();
    }

    private static String objectMapBody() {
        return """
                final Object[] values = new Object[length];
                for (int index = 0; index < values.length; index++) {
                    values[index] = invokeIndexed(transform, elements[index], index);
                }
                return array(values, elementDescriptor);
            """
            .indent(4)
            .stripTrailing();
    }

    private static String objectReduceBody() {
        return """
                Object result = initial;
                for (int index = 0; index < length; index++) {
                    result = reducer.type().parameterCount() == 3
                        ? reducer.invoke(result, elements[index], index)
                        : reducer.invoke(result, elements[index]);
                }
                return result;
            """.indent(4).stripTrailing();
    }

    private static String objectComparatorHelper() {
        return """
                private void sortObjectArray(final MethodHandle comparator) throws Throwable {
                    try {
                        Arrays.sort(elements, 0, length, (left, right) -> {
                            try {
                                return (int) comparator.invoke(left, right);
                            }
                            catch (final RuntimeException | Error exception) {
                                throw exception;
                            }
                            catch (final Throwable exception) {
                                throw new ComparatorFailure(exception);
                            }
                        });
                    }
                    catch (final ComparatorFailure exception) {
                        throw exception.getCause();
                    }
                }

                private static final class ComparatorFailure extends RuntimeException {
                    private ComparatorFailure(final Throwable cause) {
                        super(cause);
                    }
                }
            """
            .stripTrailing();
    }
}
