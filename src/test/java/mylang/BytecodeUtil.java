package mylang;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

public class BytecodeUtil {

    public static void verify(final byte[] bytecode) {
        final var writer = new StringWriter();
        final var printWriter = new PrintWriter(writer);
        final var classReader = new ClassReader(bytecode);

        CheckClassAdapter.verify(classReader, false, printWriter);

        final String errors = writer.toString();

        if (!errors.isEmpty()) {
            throw new AssertionError("Invalid bytecode:\n" + errors);
        }
    }

    public static String toString(final byte[] bytecode) {
        final var writer = new StringWriter();
        final var printWriter = new PrintWriter(writer);
        final var classReader = new ClassReader(bytecode);
        final var textifier = new Textifier();
        final var visitor = new TraceClassVisitor(null, textifier, printWriter);

        classReader.accept(visitor, 0);

        return writer.toString().stripTrailing();
    }

}
