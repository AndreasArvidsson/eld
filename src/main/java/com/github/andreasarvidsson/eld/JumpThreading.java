package com.github.andreasarvidsson.eld;

import static java.lang.classfile.Opcode.GOTO;
import static java.lang.classfile.Opcode.GOTO_W;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ExceptionCatch;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Removes jumps through empty blocks after code generation. */
final class JumpThreading {
    private JumpThreading() {}

    static byte[] optimize(final ClassFile classFile, final byte[] bytes) {
        // Removing an orphaned jump block can make its predecessor jump
        // directly to the next instruction.
        return optimizePass(classFile, optimizePass(classFile, bytes));
    }

    private static byte[] optimizePass(
        final ClassFile classFile,
        final byte[] bytes
    ) {
        return classFile
            .transformClass(classFile.parse(bytes), (classBuilder, element) -> {
                if (element instanceof MethodModel method) {
                    classBuilder.transformMethod(
                        method,
                        (methodBuilder, methodElement) -> {
                            if (methodElement instanceof CodeModel code) {
                                final List<CodeElement> elements =
                                    code.elementList();
                                final IdentityHashMap<Label, Integer> targets =
                                    targetInstructions(elements);
                                final Set<Label> referenced =
                                    referencedLabels(elements, targets);
                                final int[] index = {0};
                                methodBuilder.transformCode(
                                    code,
                                    (codeBuilder, codeElement) -> {
                                        final int position = index[0]++;
                                        if (
                                            codeElement instanceof BranchInstruction branch
                                        ) {
                                            if (
                                                isGoto(branch) && orphanedGoto(
                                                    elements,
                                                    position,
                                                    referenced
                                                )
                                            ) {
                                                return;
                                            }
                                            final Label target =
                                                resolve(
                                                    branch.target(),
                                                    elements,
                                                    targets
                                                );
                                            final int next =
                                                firstInstruction(
                                                    elements,
                                                    position + 1
                                                );
                                            if (
                                                isGoto(branch) && next >= 0
                                                    && next == targets
                                                        .getOrDefault(
                                                            target,
                                                            -1
                                                        )
                                            ) {
                                                return;
                                            }
                                            codeBuilder.with(
                                                BranchInstruction
                                                    .of(branch.opcode(), target)
                                            );
                                        }
                                        else {
                                            codeBuilder.with(codeElement);
                                        }
                                    }
                                );
                            }
                            else {
                                methodBuilder.with(methodElement);
                            }
                        }
                    );
                }
                else {
                    classBuilder.with(element);
                }
            });
    }

    private static IdentityHashMap<Label, Integer> targetInstructions(
        final List<CodeElement> elements
    ) {
        final IdentityHashMap<Label, Integer> targets = new IdentityHashMap<>();
        for (int index = 0; index < elements.size(); index++) {
            if (elements.get(index) instanceof LabelTarget target) {
                targets
                    .put(target.label(), firstInstruction(elements, index + 1));
            }
        }
        return targets;
    }

    private static Set<Label> referencedLabels(
        final List<CodeElement> elements,
        final IdentityHashMap<Label, Integer> targets
    ) {
        final Set<Label> referenced =
            Collections.newSetFromMap(new IdentityHashMap<>());
        for (final CodeElement element : elements) {
            if (element instanceof BranchInstruction branch) {
                referenced.add(resolve(branch.target(), elements, targets));
            }
            else if (element instanceof LookupSwitchInstruction lookup) {
                referenced.add(lookup.defaultTarget());
                for (final SwitchCase branch : lookup.cases()) {
                    referenced.add(branch.target());
                }
            }
            else if (element instanceof TableSwitchInstruction table) {
                referenced.add(table.defaultTarget());
                for (final SwitchCase branch : table.cases()) {
                    referenced.add(branch.target());
                }
            }
            else if (element instanceof ExceptionCatch handler) {
                referenced.add(handler.handler());
            }
        }
        return referenced;
    }

    private static boolean orphanedGoto(
        final List<CodeElement> elements,
        final int position,
        final Set<Label> referenced
    ) {
        boolean hasLabel = false;
        for (int index = position - 1; index >= 0; index--) {
            final CodeElement element = elements.get(index);
            if (element instanceof LabelTarget label) {
                hasLabel = true;
                if (referenced.contains(label.label())) {
                    return false;
                }
            }
            else if (element instanceof Instruction instruction) {
                return hasLabel
                    && instruction instanceof BranchInstruction branch
                    && isGoto(branch);
            }
        }
        return false;
    }

    private static int firstInstruction(
        final List<CodeElement> elements,
        final int start
    ) {
        for (int index = start; index < elements.size(); index++) {
            if (elements.get(index) instanceof Instruction) {
                return index;
            }
        }
        return -1;
    }

    private static Label resolve(
        final Label original,
        final List<CodeElement> elements,
        final IdentityHashMap<Label, Integer> targets
    ) {
        Label target = original;
        final Set<Label> seen =
            Collections.newSetFromMap(new IdentityHashMap<>());
        while (seen.add(target)) {
            final Integer index = targets.get(target);
            if (
                index == null || index < 0
                    || !(elements
                        .get(index) instanceof BranchInstruction branch)
                    || !isGoto(branch)
            ) {
                break;
            }
            target = branch.target();
        }
        return target;
    }

    private static boolean isGoto(final BranchInstruction branch) {
        return branch.opcode() == GOTO || branch.opcode() == GOTO_W;
    }
}
