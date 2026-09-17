package mylang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import mylang.lexer.Lexer;
import mylang.lexer.Token;
import mylang.parser.Parser;
import mylang.parser.Program;
import mylang.semantic.SemanticAnalyzer;
import mylang.semantic.SemanticModel;

public abstract class Compiler {

    public static Map<String, byte[]> compile(final String source) {
        final Lexer lexer = new Lexer(source);
        final List<Token> tokens = lexer.getTokens();
        final Parser parser = new Parser(tokens);
        final Program program = parser.parse();
        final SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer();
        final SemanticModel semanticModel = semanticAnalyzer.analyze(program);
        final BytecodeGenerator bytecodeGenerator =
            new BytecodeGenerator(program, semanticModel);
        return bytecodeGenerator.generateClasses();
    }

    public static Map<String, byte[]> compile(final Path sourcePath)
        throws IOException {
        final String source = Files.readString(sourcePath);
        return Compiler.compile(source);
    }
}
