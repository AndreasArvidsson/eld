package com.github.andreasarvidsson.eld;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.IdentityHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

public class BytecodeUtil {

    public static void verify(final byte[] bytecode) {
        verify(bytecode, BytecodeUtil.class.getClassLoader());
    }

    public static void verify(final byte[] bytecode, final ClassLoader loader) {
        final var errors =
            ClassFile
                .of(
                    ClassFile.ClassHierarchyResolverOption
                        .of(ClassHierarchyResolver.ofClassLoading(loader))
                )
                .verify(bytecode);
        if (!errors.isEmpty()) {
            throw new AssertionError("Invalid bytecode:\n" + errors);
        }
    }

    public static String toString(final byte[] bytecode) {
        final var model = ClassFile.of().parse(bytecode);
        final StringBuilder text = new StringBuilder();
        text.append("class ")
            .append(model.thisClass().asInternalName())
            .append(" extends ")
            .append(model.superclass().orElseThrow().asInternalName())
            .append(" version ")
            .append(model.majorVersion())
            .append('.')
            .append(model.minorVersion())
            .append(" flags ")
            .append(model.flags().flags().stream().sorted().toList())
            .append('\n');
        model.interfaces()
            .forEach(
                contract -> text.append("  implements ")
                    .append(contract.asInternalName())
                    .append('\n')
            );
        model.findAttribute(Attributes.signature())
            .ifPresent(
                signature -> text.append("  signature ")
                    .append(signature.signature().stringValue())
                    .append('\n')
            );
        model.findAttribute(Attributes.nestHost())
            .ifPresent(
                nest -> text.append("  nest host ")
                    .append(nest.nestHost().asInternalName())
                    .append('\n')
            );
        model.findAttribute(Attributes.nestMembers())
            .ifPresent(
                nest -> nest.nestMembers()
                    .forEach(
                        member -> text.append("  nest member ")
                            .append(member.asInternalName())
                            .append('\n')
                    )
            );
        model.findAttribute(Attributes.innerClasses())
            .ifPresent(
                inner -> inner.classes()
                    .forEach(
                        entry -> text.append("  inner class ")
                            .append(entry.innerClass().asInternalName())
                            .append(" flags ")
                            .append(entry.flags().stream().sorted().toList())
                            .append('\n')
                    )
            );
        for (final var field : model.fields()) {
            text.append("  field ")
                .append(field.fieldName().stringValue())
                .append(' ')
                .append(field.fieldType().stringValue())
                .append(" flags ")
                .append(field.flags().flags().stream().sorted().toList());
            final Object value = constantValue(field);
            if (value != null) {
                text.append(" = ").append(constantText(value));
            }
            text.append('\n');
            field.findAttribute(Attributes.signature())
                .ifPresent(
                    signature -> text.append("    signature ")
                        .append(signature.signature().stringValue())
                        .append('\n')
                );
        }
        for (final var method : model.methods()) {
            text.append("  method ")
                .append(method.methodName().stringValue())
                .append(method.methodType().stringValue())
                .append(" flags ")
                .append(method.flags().flags().stream().sorted().toList())
                .append('\n');
            method.findAttribute(Attributes.signature())
                .ifPresent(
                    signature -> text.append("    signature ")
                        .append(signature.signature().stringValue())
                        .append('\n')
                );
            final IdentityHashMap<Label, Integer> labels =
                new IdentityHashMap<>();
            method.code().ifPresent(code -> {
                for (final var element : code) {
                    if (element instanceof LabelTarget target) {
                        labels.computeIfAbsent(
                            target.label(),
                            ignored -> labels.size()
                        );
                    }
                }
                for (final var element : code) {
                    if (element instanceof LabelTarget target) {
                        text.append("   L")
                            .append(labels.get(target.label()))
                            .append(":\n");
                    }
                    else if (element instanceof Instruction instruction) {
                        text.append("    ").append(instruction.opcode());
                        switch (instruction) {
                            case LoadInstruction load ->
                                text.append(' ').append(load.slot());
                            case StoreInstruction store ->
                                text.append(' ').append(store.slot());
                            case IncrementInstruction increment ->
                                text.append(' ')
                                    .append(increment.slot())
                                    .append(' ')
                                    .append(increment.constant());
                            case ConstantInstruction constant -> text
                                .append(' ')
                                .append(constantText(constant.constantValue()));
                            case FieldInstruction field -> text.append(' ')
                                .append(field.owner().asInternalName())
                                .append('.')
                                .append(field.name().stringValue())
                                .append(' ')
                                .append(field.type().stringValue());
                            case InvokeInstruction call -> text.append(' ')
                                .append(call.owner().asInternalName())
                                .append('.')
                                .append(call.name().stringValue())
                                .append(call.type().stringValue());
                            case NewObjectInstruction creation -> text
                                .append(' ')
                                .append(creation.className().asInternalName());
                            case NewReferenceArrayInstruction array -> text
                                .append(' ')
                                .append(array.componentType().asInternalName());
                            case NewPrimitiveArrayInstruction array ->
                                text.append(' ').append(array.typeKind());
                            case TypeCheckInstruction check -> text.append(' ')
                                .append(check.type().asInternalName());
                            case BranchInstruction branch -> text.append(" L")
                                .append(labels.get(branch.target()));
                            case TableSwitchInstruction table -> {
                                text.append(' ')
                                    .append(table.lowValue())
                                    .append("..")
                                    .append(table.highValue())
                                    .append(" default L")
                                    .append(labels.get(table.defaultTarget()));
                                table.cases()
                                    .forEach(
                                        entry -> text.append(' ')
                                            .append(entry.caseValue())
                                            .append(":L")
                                            .append(labels.get(entry.target()))
                                    );
                            }
                            case LookupSwitchInstruction lookup -> {
                                text.append(" default L")
                                    .append(labels.get(lookup.defaultTarget()));
                                lookup.cases()
                                    .forEach(
                                        entry -> text.append(' ')
                                            .append(entry.caseValue())
                                            .append(":L")
                                            .append(labels.get(entry.target()))
                                    );
                            }
                            default -> {
                            }
                        }
                        text.append('\n');
                    }
                }
                text.append("    max stack ")
                    .append(
                        ((java.lang.classfile.attribute.CodeAttribute) code)
                            .maxStack()
                    )
                    .append(" max locals ")
                    .append(
                        ((java.lang.classfile.attribute.CodeAttribute) code)
                            .maxLocals()
                    )
                    .append('\n');
            });
        }
        return text.toString().stripTrailing();
    }

    private static String constantText(final @Nullable Object value) {
        if (value instanceof String string) {
            return '"' + string.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + '"';
        }
        return String.valueOf(value);
    }

    static List<Instruction> instructions(final MethodModel method) {
        return method.code()
            .stream()
            .flatMap(CodeModel::elementStream)
            .filter(Instruction.class::isInstance)
            .map(Instruction.class::cast)
            .toList();
    }

    static @Nullable Object constantValue(final FieldModel field) {
        return field.findAttribute(Attributes.constantValue())
            .map(attribute -> (Object) attribute.constant().constantValue())
            .orElse(null);
    }

    static Label switchTarget(
        final TableSwitchInstruction table,
        final int key
    ) {
        return table.cases()
            .stream()
            .filter(entry -> entry.caseValue() == key)
            .map(SwitchCase::target)
            .findFirst()
            .orElse(table.defaultTarget());
    }

}
