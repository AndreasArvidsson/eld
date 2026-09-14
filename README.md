# my-lang

Run these commands from the project root with Java 21 or later and Maven 3.9 or later.

## Running tests

Run all tests:

```bash
mvn test
```

Run only the lexer tests:

```bash
mvn test -Dtest=FixtureTest
```

## Lexer fixtures

`FixtureTest` discovers all `.fixture` files under `src/test/resources/fixtures`, including subdirectories. Each file contains lexer input followed by a blank line and `--- TOKENS ---` (the delimiter is `\n\n--- TOKENS ---\n`). Both newlines before the header belong to the separator, not the source input. Any additional newlines before that separator remain part of the input. Windows and Unix line endings are supported.

For example:

```text
const foo = 10

--- TOKENS ---

CONST (1:1-1:6)
IDENTIFIER "foo" (1:7-1:10)
EQUAL (1:11-1:12)
INTEGER_LITERAL "10" (1:13-1:15)
```

## Generating or updating expected output

1. Create a `.fixture` file containing just the source code, or edit the input in an existing fixture. The separator and expected output can initially be omitted.
2. Run update mode to generate or replace expected output:

   ```bash
   mvn test -DupdateFixtures=true
   ```

   This runs all tests. Currently only `FixtureTest` supports fixture updates; other tests run normally. To run only the lexer tests in update mode:

   ```bash
   mvn test -Dtest=FixtureTest -DupdateFixtures=true
   ```

3. Review the generated output in the source fixtures, for example with `git diff`. Update mode writes the lexer's actual output instead of asserting that it is correct, so check the results before accepting them.
4. Run `mvn test` again without the update flag to assert against the reviewed output.

Update mode adds missing separators and expected output, preserves the source input (including trailing newlines) after normalizing CRLF to LF, and writes to `src/test/resources/fixtures`, not the build output directory. Lexer and parser exceptions are written as expected output. Repeated updates do not add newlines to the input. Normal test runs require generated expectations and do not modify fixtures.
