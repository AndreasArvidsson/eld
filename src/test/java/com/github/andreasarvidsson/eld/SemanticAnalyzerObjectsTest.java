package com.github.andreasarvidsson.eld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.github.andreasarvidsson.eld.lexer.Lexer;
import com.github.andreasarvidsson.eld.parser.IdentifierDeclaration;
import com.github.andreasarvidsson.eld.parser.IdentifierExpression;
import com.github.andreasarvidsson.eld.parser.CallExpression;
import com.github.andreasarvidsson.eld.parser.Mutability;
import com.github.andreasarvidsson.eld.parser.ObjectExpression;
import com.github.andreasarvidsson.eld.parser.ObjectSpread;
import com.github.andreasarvidsson.eld.parser.Parser;
import com.github.andreasarvidsson.eld.semantic.BuiltinType;
import com.github.andreasarvidsson.eld.semantic.ClassType;
import com.github.andreasarvidsson.eld.semantic.FunctionSymbol;
import com.github.andreasarvidsson.eld.semantic.FunctionType;
import com.github.andreasarvidsson.eld.semantic.InterfaceContract;
import com.github.andreasarvidsson.eld.semantic.InterfaceType;
import com.github.andreasarvidsson.eld.semantic.Scope;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzer;
import com.github.andreasarvidsson.eld.semantic.SemanticAnalyzerObjects;
import com.github.andreasarvidsson.eld.semantic.SemanticContext;
import com.github.andreasarvidsson.eld.semantic.SemanticException;
import com.github.andreasarvidsson.eld.semantic.SemanticModel;
import com.github.andreasarvidsson.eld.semantic.Type;
import com.github.andreasarvidsson.eld.semantic.VariableSymbol;

class SemanticAnalyzerObjectsTest {
    @Test
    void resolvesOverloadWithContextualObjectLiteral() {
        final var program = new Parser(new Lexer("""
            interface Empty {}
            func choose(value: Empty) string { return "object"; }
            func choose(value: i32) string { return "number"; }
            """).getTokens()).parse();
        final SemanticAnalyzer analyzer = new SemanticAnalyzer();
        final SemanticModel model = analyzer.analyze(program);
        final Scope scope = new Scope(null);
        model.getFunctionDeclarations().keySet().forEach(scope::declare);
        final Range range = new Range(1, 1, 1, 3);
        final IdentifierExpression callee =
            new IdentifierExpression("choose", range);
        final ObjectExpression argument =
            new ObjectExpression(List.of(), range);
        final CallExpression call =
            new CallExpression(callee, List.of(argument), range);

        assertEquals(
            BuiltinType.STRING,
            analyzer
                .analyzeExpression(call, new SemanticContext(scope, null, 0))
        );
        assertEquals(
            List.of(new InterfaceType("Empty")),
            ((FunctionSymbol) model.getReference(callee)).type()
                .parameterTypes()
        );
    }

    @Test
    void rejectsSpreadOfOverloadedInterface() {
        assertOverloadedSpreadRejected("""
            interface Printer {
                func describe(value: i32) string;
                func describe(value: string) string;
            }
            class Both implements Printer {
                public func describe(value: i32) string { return "number"; }
                public func describe(value: string) string { return "text"; }
            }
            """, new InterfaceType("Printer"));
    }

    @Test
    void rejectsSpreadOfInheritedClassOverloads() {
        assertOverloadedSpreadRejected("""
            class Base {
                public func describe(value: i32) string { return "number"; }
            }
            class Child extends Base {
                public func describe(value: string) string { return "text"; }
            }
            """, new ClassType("Child"));
    }

    private static void assertOverloadedSpreadRejected(
        final String source,
        final Type spreadType
    ) {
        final var program = new Parser(new Lexer(source).getTokens()).parse();
        final SemanticAnalyzer analyzer = new SemanticAnalyzer();
        final SemanticModel model = analyzer.analyze(program);
        final Range range = new Range(1, 1, 1, 3);
        final Scope scope = new Scope(null);
        scope.declare(
            new VariableSymbol(
                new IdentifierDeclaration("value", range),
                spreadType,
                Mutability.CONST
            )
        );
        final ObjectExpression object =
            new ObjectExpression(
                List.of(
                    new ObjectSpread(
                        new IdentifierExpression("value", range),
                        range
                    )
                ),
                range
            );
        final SemanticException exception =
            assertThrows(
                SemanticException.class,
                () -> new SemanticAnalyzerObjects(analyzer, model)
                    .inferSpreadObject(
                        object,
                        new SemanticContext(scope, null, 0)
                    )
            );
        assertTrue(
            exception.getMessage()
                .contains("Cannot spread overloaded method 'describe'"),
            exception.getMessage()
        );
    }

    @Test
    void rejectsOverloadedInterfaceMethod() {
        final Range range = new Range(1, 1, 1, 3);
        final InterfaceType type = new InterfaceType("Printer");
        final FunctionSymbol integerMethod =
            new FunctionSymbol(
                new IdentifierDeclaration("describe", range),
                new FunctionType(List.of(BuiltinType.I32), BuiltinType.STRING)
            );
        final FunctionSymbol stringMethod =
            new FunctionSymbol(
                new IdentifierDeclaration("describe", range),
                new FunctionType(
                    List.of(BuiltinType.STRING),
                    BuiltinType.STRING
                )
            );
        final SemanticModel model = new SemanticModel();
        model.setInterface(
            type,
            new InterfaceContract(
                List.of(),
                Map.of(),
                Map.of("describe", integerMethod),
                Map.of("describe", List.of(integerMethod, stringMethod))
            )
        );

        final SemanticException exception =
            assertThrows(
                SemanticException.class,
                () -> new SemanticAnalyzerObjects(new SemanticAnalyzer(), model)
                    .analyzeObjectExpression(
                        new ObjectExpression(List.of(), range),
                        new SemanticContext(new Scope(null), null, 0),
                        type
                    )
            );
        assertEquals(
            "Object literal cannot implement overloaded interface method 'describe' of Printer at 1:1-1:3",
            exception.getMessage()
        );
    }
}
