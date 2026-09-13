# my-lang

Run these commands from the project root with Java 21 or later and Maven 3.9 or later.

## Running tests

Run all tests:

```bash
mvn test
```

Run only the lexer tests:

```bash
mvn test -Dtest=LexerTest
```

## Lexer fixtures

`LexerTest` discovers all `.fixture` files under `src/test/resources/fixtures/lexer`, including subdirectories. Each file contains lexer input followed by expected output, separated by a line containing `---` (the delimiter is `\n---\n`). Windows and Unix line endings are supported.

For example:

```text
const foo = 10
---
CONST (1:1-1:6)
IDENTIFIER "foo" (1:7-1:10)
EQUAL (1:11-1:12)
INTEGER_LITERAL "10" (1:13-1:15)
```

## Generating or updating expected output

1. Create a `.fixture` file containing just the input, or edit the input in an existing fixture.
2. Run update mode to generate or replace expected output:

   ```bash
   mvn test -DupdateFixtures=true
   ```

   This runs all tests. Currently only `LexerTest` supports fixture updates; other tests run normally. To run only the lexer tests in update mode:

   ```bash
   mvn test -Dtest=LexerTest -DupdateFixtures=true
   ```

3. Review the generated output in the source fixtures, for example with `git diff`. Update mode writes the lexer's actual output instead of asserting that it is correct, so check the results before accepting them.
4. Run `mvn test` again without the update flag to assert against the reviewed output.

Update mode adds the delimiter when it is missing, preserves the input and its line-ending style, and writes to `src/test/resources/fixtures/lexer`, not the build output directory. If lexing a fixture fails, that fixture is left untouched. Normal test runs do not modify fixtures.
