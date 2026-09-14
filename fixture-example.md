```
const foo = 10

--- TOKENS ---
CONST (1:1-1:6)
IDENTIFIER "foo" (1:7-1:10)
EQUAL (1:11-1:12)
INTEGER_LITERAL "10" (1:13-1:15)

--- AST ---
CompilationUnit
  VariableDeclaration
    mutability: CONST
    name: foo
    type: <inferred>
    initializer:
      IntegerLiteral 10

--- SEMANTIC ---
CompilationUnit
  VariableDeclaration
    mutability: CONST
    name: foo
    type: Int
    initializer:
      IntegerLiteral 10 : Int

--- BYTECODE ---
class Module
  field static final foo I = 10

--- OUTPUT ---
```

- TOKENS recognizes const/name/literal
- AST recognizes a constant declaration
- SEMANTIC infers foo: Int
- BYTECODE lowers Int → JVM I and const → static final field
- OUTPUT nothing, because declaring foo has no observable output
