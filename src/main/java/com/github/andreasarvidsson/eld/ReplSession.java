package com.github.andreasarvidsson.eld;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import com.github.andreasarvidsson.eld.parser.ClassDeclaration;
import com.github.andreasarvidsson.eld.parser.InterfaceDeclaration;
import java.util.Map;
import com.github.andreasarvidsson.eld.lexer.Lexer;
import com.github.andreasarvidsson.eld.lexer.Token;
import com.github.andreasarvidsson.eld.parser.BlockItem;
import com.github.andreasarvidsson.eld.parser.Parser;
import com.github.andreasarvidsson.eld.parser.Program;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzer;
import com.github.andreasarvidsson.eld.semantic.SemanticModel;

/** Compiles each submission into a subclass of the previous module.
 * Inherited static fields and methods retain their identity and state. */
public final class ReplSession {
    private final ModuleLoader loader = new ModuleLoader();
    private final List<BlockItem> items = new ArrayList<>();
    private final Map<String, String> classOwners = new HashMap<>();
    private String parent = "java/lang/Object";
    private int sequence;

    public void evaluate(final String source)
        throws ReflectiveOperationException {
        final List<Token> tokens = new Lexer(source).getTokens();
        final Program submission = new Parser(tokens).parse();
        if (submission.items().isEmpty()) {
            return;
        }
        final List<BlockItem> combined = new ArrayList<>(items);
        combined.addAll(submission.items());
        final Program program = new Program(combined, submission.range());
        final SemanticModel model = new SemanticAnalyzer().analyze(program);
        final String name = "Repl" + sequence++;
        final Map<String, byte[]> classes =
            new BytecodeGenerator(
                program,
                model,
                name,
                parent,
                items.size(),
                classOwners
            ).generateClasses();
        loader.add(classes);
        final Class<?> module = loader.loadClass(name);
        // Keep declarations even if execution fails: earlier mutations cannot be rolled back.
        items.addAll(submission.items());
        for (final BlockItem item : submission.items()) {
            if (item instanceof ClassDeclaration declaration) {
                classOwners.put(
                    declaration.name().name(),
                    name + "$" + declaration.name().name()
                );
            }
            else if (item instanceof InterfaceDeclaration declaration) {
                classOwners.put(
                    declaration.name().name(),
                    name + "$" + declaration.name().name()
                );
            }
        }
        for (final var type : model.getInterfaceTypes()) {
            if (type.name().startsWith("$spread")) {
                classOwners.putIfAbsent(type.name(), name + "$" + type.name());
            }
        }
        parent = name;
        try {
            module.getMethod("$eval").invoke(null);
        }
        catch (final InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }
}
