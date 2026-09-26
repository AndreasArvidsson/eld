# Add Multiline Strings to Eld

Implement dedicated **multiline string literals** using backticks:

```eld
const message = `
Hello
World
`;
```

Normal double-quoted strings remain **single-line only**:

```eld
const message = "Hello World";
```

Do not allow an unescaped physical newline inside a normal `"..."` string.

This keeps ordinary string errors easy to diagnose and gives multiline strings their own explicit syntax and formatting rules.

## Syntax

Use a backtick as the multiline string delimiter:

```eld
`
content
`
```

Example:

```eld
const text = `
Hello world!

This is a multiline string.
`;
```

The value should be:

```text
Hello world!

This is a multiline string.
```

The delimiter lines themselves do not introduce leading or trailing newlines.

## Normal Strings Remain Single-Line

Existing strings continue to work normally:

```eld
const name = "Andreas";
const message = "Hello\nWorld";
```

A physical newline before the closing quote is an error:

```eld
const message = "Hello
World";
```

The lexer should report an unterminated string at the end of the first line rather than continuing to consume subsequent source lines looking for a closing quote.

Escaped newlines such as `\n` remain valid:

```eld
const message = "Hello\nWorld";
```

## Multiline Delimiter Placement

For the initial implementation, require multiline delimiters to delimit complete lines.

The opening `` ` `` must be followed immediately by a newline:

```eld
const text = `
Hello
World
`;
```

Do not initially support:

```eld
const text = `Hello
World
`;
```

Likewise, the closing delimiter should appear on its own line, apart from indentation:

```eld
const text = `
Hello
World
`;
```

Do not initially support content followed by the closing delimiter on the same line:

```eld
const text = `
Hello
World`;
```

These restrictions make newline and indentation semantics substantially simpler. They can be relaxed later without changing the basic multiline-string syntax.

## Leading and Trailing Newlines

The newline immediately following the opening `` ` `` is structural and is **not part of the string value**.

Similarly, the newline immediately before the closing `` ` `` is structural and is **not part of the string value**.

Therefore:

```eld
const text = `
Hello
World
`;
```

has the value:

```text
Hello
World
```

not:

```text

Hello
World

```

Explicit blank lines inside the content are preserved:

```eld
const text = `
Hello

World
`;
```

has the value:

```text
Hello

World
```

## Indentation

Multiline strings should support indentation stripping so that they can be naturally embedded inside indented Eld code.

Use the indentation of the **closing delimiter** as the indentation prefix removed from content lines.

For example:

```eld
func run() {
    const text = `
        Hello
        World
        `;
}
```

has the value:

```text
Hello
World
```

The eight spaces before the closing `` ` `` define the indentation removed from each non-empty content line.

This allows source indentation to remain readable without accidentally becoming part of the runtime string.

Another example:

```eld
func run() {
    if (true) {
        const text = `
            first
            second
            third
            `;
    }
}
```

produces:

```text
first
second
third
```

## Additional Content Indentation

Indentation beyond the closing delimiter's indentation is significant and must be preserved.

For example:

```eld
const text = `
    parent
        child
    sibling
    `;
```

produces:

```text
parent
    child
sibling
```

The common structural indentation is removed, while the additional indentation before `child` remains part of the value.

## Insufficient Indentation

Every non-empty content line should have at least the indentation used by the closing delimiter.

For example:

```eld
func run() {
    const text = `
        Hello
      World
        `;
}
```

should be rejected because `World` does not have enough indentation to remove the closing delimiter's indentation prefix.

Produce a clear lexer/parser error identifying the offending line.

Blank lines do not need to satisfy this indentation requirement.

## Blank Lines

Blank lines inside a multiline string are preserved as newline characters but should not retain structural whitespace.

For example:

```eld
const text = `
    first

    second
    `;
```

should conceptually produce:

```text
first

second
```

rather than retaining spaces from an otherwise blank source line.

## Escape Sequences

Multiline strings are **normal strings**, not raw strings.

They should support the same escape sequences as ordinary strings.

For example:

```eld
const text = `
Hello\tWorld
This contains a quote: \"
This contains a backslash: \\
`;
```

Escape processing should reuse the existing normal-string escape semantics wherever possible.

A physical newline in a multiline string naturally represents a newline and does not need to be written as `\n`.

For example:

```eld
const text = `
Hello
World
`;
```

is equivalent in value to:

```eld
const text = "Hello\nWorld";
```

assuming no additional trailing newline.

## Backtick Inside the String

An unescaped `` ` `` terminates the multiline string.

If existing Eld escape rules permit escaping quotes, provide a way to represent backticks through those existing escape semantics rather than introducing special delimiter-specific behavior unnecessarily.

Add tests covering quotes and sequences of multiple quotes inside multiline strings.

## AST

Multiline strings should remain string literal expressions.

Do not introduce a new semantic type.

Depending on the existing AST architecture, either:

- reuse `LiteralExpression` with `kind: STRING`, or
- record that the source representation was multiline if that information is useful to later compiler phases.

The semantic type remains:

```text
string
```

For example:

```eld
const text = `
Hello
World
`;
```

should conceptually still produce:

```text
LiteralExpression
  kind: STRING
  text: ...
```

Preserve the source range across the complete multiline literal.

The AST should preserve whatever source representation is normally preserved for string literals, while the compiler must also have access to the processed runtime value.

Follow the existing Eld literal architecture rather than creating an unrelated representation solely for multiline strings.

## Tokens

Use the existing string literal token if practical:

```text
STRING_LITERAL
```

A multiline string does not need a distinct semantic token type unless distinguishing the lexical form materially simplifies the implementation.

The token range must cover the complete multiline literal, including its delimiters.

For example:

```eld
const text = `
Hello
World
`;
```

should have a string token spanning from the opening `` ` `` through the closing `` ` ``.

Ensure line/column tracking continues correctly after lexing a multiline literal.

This is particularly important because multiline tokens advance the lexer across multiple physical source lines.

## Semantic Analysis

A multiline string has exactly the same semantic type as an ordinary string:

```text
string
```

Therefore:

```eld
const single = "Hello";
const multi = `
Hello
World
`;
```

should infer:

```text
single: string
multi: string
```

Multiline strings should work anywhere an ordinary string expression works:

```eld
print(`
Hello
World
`);
```

and:

```eld
func consume(value: string) {}

consume(`
Hello
World
`);
```

No special conversion should be required.

## Interaction With Literal Types

If literal types have been implemented, multiline strings should initially behave like ordinary string expressions.

Do not introduce special multiline literal types.

If explicit string literal types are supported, avoid expanding the scope of this change unnecessarily. The primary goal here is runtime multiline string literals.

## Bytecode

Multiline strings require no special JVM representation.

After escape processing, indentation removal, and newline normalization, emit the resulting value as a normal Java/JVM string constant using the existing string bytecode generation.

For example:

```eld
const text = `
Hello
World
`;
```

should effectively emit the same runtime string as:

```eld
const text = "Hello\nWorld";
```

No runtime indentation processing should be necessary.

All multiline-string processing should happen at compile time.

## Line Endings

Normalize source line endings inside multiline strings to `\n`.

Thus source files using:

```text
LF
```

or:

```text
CRLF
```

should produce the same runtime string.

This prevents the runtime value of an Eld program from depending on whether the source file was checked out with Unix or Windows line endings.

The lexer must still maintain correct source positions for diagnostics.

## Error Handling

Add clear diagnostics for at least:

### Unterminated normal string

```eld
const value = "hello
```

The error should occur at the end of that source line.

### Unterminated multiline string

```eld
const value = `
hello
world
```

Report an unterminated multiline string.

### Content with insufficient indentation

```eld
func run() {
    const value = `
        hello
      world
        `;
}
```

Report that a content line has less indentation than required by the closing delimiter.

### Invalid opening delimiter placement

If the initial implementation requires a newline immediately after `` ` ``, reject:

```eld
const value = `hello
world
`;
```

### Invalid closing delimiter placement

If the initial implementation requires the closing delimiter on its own line, reject:

```eld
const value = `
hello
world`;
```

## Tests

Add lexer, parser, semantic, bytecode, and output fixtures where appropriate.

At minimum test:

### Basic multiline string

```eld
const value = `
Hello
World
`;
print(value);
```

Expected output:

```text
Hello
World
```

### Embedded blank line

```eld
const value = `
Hello

World
`;
```

### Indented source

```eld
func run() {
    const value = `
        Hello
        World
        `;
}
```

Expected value:

```text
Hello
World
```

### Significant additional indentation

```eld
const value = `
    parent
        child
    sibling
    `;
```

Expected value:

```text
parent
    child
sibling
```

### Escape processing

```eld
const value = `
Hello\tWorld
Quote: \"
`;
```

Verify that normal string escapes are processed.

### Ordinary string regression

Verify that existing strings remain unchanged:

```eld
const value = "Hello\nWorld";
```

### Ordinary physical newline rejection

Verify that this remains invalid:

```eld
const value = "Hello
World";
```

### Unterminated multiline literal

Verify that:

```eld
const value = `
Hello
```

produces an appropriate lexer error.

### CRLF/LF normalization

Compile equivalent source using CRLF and LF and verify that both result in the same runtime string containing `\n`.

### Source positions after multiline strings

Verify that tokens and diagnostics following a multiline literal have correct line and column ranges.

This is an important lexer regression test.

## Future Raw Strings

Do **not** add raw strings as part of this work.

However, avoid designing multiline strings in a way that prevents a future orthogonal raw-string syntax such as:

```eld
const path = r"C:\foo\bar";
```

and:

```eld
const text = r`
C:\foo\bar
\d+\.\d+
`;
```

The intended future model is:

```text
"..."       normal single-line string
`...`   normal multiline string

r"..."      raw single-line string       // future
r`...`  raw multiline string         // future
```

Rawness and multiline formatting should remain independent concepts.

## Scope Summary

Implement:

```text
"..."       single-line normal string
`...`   multiline normal string
```

Normal strings:

- Cannot contain physical newlines.
- Continue supporting existing escape sequences.

Multiline strings:

- Require dedicated `` ` `` delimiters.
- Initially require opening and closing delimiters on their own structural lines.
- Do not include structural opening/closing newlines in the value.
- Strip indentation based on the closing delimiter.
- Preserve indentation beyond that structural indentation.
- Preserve meaningful blank lines.
- Use the same escape processing as ordinary strings.
- Normalize physical source line endings to `\n`.
- Have semantic type `string`.
- Use the normal JVM string representation.
- Perform all formatting/escape processing at compile time.

Do not add raw strings or floating/new string types as part of this feature.
