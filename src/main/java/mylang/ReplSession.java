package mylang;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import mylang.parser.BlockItem;
import mylang.parser.Program;
import mylang.semantic.SemanticAnalyzer;

/** Compiles each submission into a subclass of the previous module.
 * Inherited static fields and methods retain their identity and state. */
public final class ReplSession {
    private final ModuleLoader loader = new ModuleLoader();
    private final List<BlockItem> items = new ArrayList<>();
    private String parent = "java/lang/Object";
    private int sequence;

    public void evaluate(final String source)
        throws ReflectiveOperationException {
        final Program submission =
            new mylang.parser.Parser(
                new mylang.lexer.Lexer(source).getTokens()
            ).parse();
        if (submission.items().isEmpty()) {
            return;
        }
        final List<BlockItem> combined = new ArrayList<>(items);
        combined.addAll(submission.items());
        final Program program = new Program(combined, submission.range());
        final var model = new SemanticAnalyzer().analyze(program);
        final String name = "Repl" + sequence++;
        final var classes =
            new BytecodeGenerator(program, model, name, parent, items.size())
                .generateClasses();
        loader.add(classes);
        final Class<?> module = loader.loadClass(name);
        // Keep declarations even if execution fails: earlier mutations cannot be rolled back.
        items.addAll(submission.items());
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
