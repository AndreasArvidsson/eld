package com.github.andreasarvidsson.eld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.github.andreasarvidsson.eld.lexer.Lexer;
import com.github.andreasarvidsson.eld.lexer.Token;
import com.github.andreasarvidsson.eld.parser.Parser;
import com.github.andreasarvidsson.eld.parser.Program;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzer;
import com.github.andreasarvidsson.eld.semantic.SemanticModel;

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
