# my-lang

Run these commands from the project root with Java 21 or later and Maven 3.9 or later.

## Command line and REPL

Build the executable application, then use the launcher in Git Bash:

```bash
mvn package
./bin.sh compile foo.iz
./bin.sh run foo.iz
./bin.sh repl
```

PowerShell and Command Prompt can use `bin.cmd` (for example, `./bin.cmd repl`).
You can also run `java -jar target/mylang-1.0-SNAPSHOT.jar repl`.
Rebuild with `mvn package` after changing the application.

`compile` writes an executable `foo.jar` next to the source, without executing it.
Run the compiled program with `java -jar foo.jar`. `run` compiles in memory and
executes the source's top-level statements. Exit codes are 0 for success, 1 for
compilation or execution errors, and 2 for invalid command-line arguments.

The REPL uses [JLine](https://jline.org/docs/intro/) for editing, session history,
keyword completion, and multiline input inside brackets or quotes. Variables,
constants, and functions persist across submissions; expressions display their
values. Earlier statements are not rerun.

```text
iz> var count = 1;
iz> func next() int { count = count + 1; return count; }
iz> next();
2
iz> count;
2
```

Use `:help`, `:reset`, or `:quit`. Ctrl-D exits and Ctrl-C cancels the current
input. Compile errors leave the session unchanged. Runtime errors retain
declarations and any mutations already performed. Redeclaring a name is an
error; use assignment for mutable variables or `:reset` to start over.

Statements require explicit terminators in files and in the REPL. Variable
declarations, expressions, `return`, `break`, and `continue` end with `;`,
including immediately before `}`. Classes, functions, and block statements
end with their closing `}`. A do-while loop ends with `while (condition);`.
Newlines do not terminate statements, so expressions and return values can
span lines. A lambda initializer still needs a semicolon after its closing
brace: `const action = () => {};`. REPL commands such as `:quit` do not need one.

## Running tests

Run all tests:

```bash
mvn test
```

Run only the lexer tests:

```bash
mvn test -Dtest=FixtureTest
```

## Running and debugging tests in VS Code

Open the project folder in VS Code with the Extension Pack for Java installed and Maven available on your PATH.

1. Open **Run and Debug** (`Ctrl+Shift+D`).
2. Select a launch configuration from the dropdown:

   | Configuration            | Behavior                                                                    |
   | ------------------------ | --------------------------------------------------------------------------- |
   | Run tests                | Run all tests and check fixture expectations.                               |
   | Run tests (subset)       | Run tests with fixture selection controlled by `testSubsetGrep.properties`. |
   | Update fixtures          | Run tests and regenerate expectations for all fixtures.                     |
   | Update fixtures (subset) | Run tests and regenerate expectations for the selected fixtures.            |

3. Set breakpoints in the test or application code, then press **F5**. Each configuration starts Maven and automatically attaches the Java debugger before tests execute.
4. When paused, use **F10** to step over, **F11** to step into, **Shift+F11** to step out, and **F5** to continue.

For either subset configuration, set the pattern in `src/test/resources/testSubsetGrep.properties` before launching. Other tests still run normally. Review fixture changes after using either update configuration.

The launch entries are defined in [`.vscode/launch.json`](.vscode/launch.json), with Maven commands in [`.vscode/tasks.json`](.vscode/tasks.json). Test output appears in the task terminal.

## Lexer fixtures

`FixtureTest` discovers all `.fixture` files under `src/test/resources/fixtures`, including subdirectories. Each file contains lexer input followed by a blank line and `--- TOKENS ---` (the delimiter is `\n\n--- TOKENS ---\n`). Both newlines before the header belong to the separator, not the source input. Any additional newlines before that separator remain part of the input. Windows and Unix line endings are supported.

For example:

```text
const foo = 10;

--- TOKENS ---

CONST (1:1-1:6)
IDENTIFIER "foo" (1:7-1:10)
EQUAL (1:11-1:12)
INTEGER_LITERAL "10" (1:13-1:15)
SEMICOLON (1:15-1:16)
```

## Generating or updating expected output

1. Create a `.fixture` file containing just the source code, or edit the input in an existing fixture. The separator and expected output can initially be omitted.
2. Run update mode to generate or replace expected output:

   ```bash
   mvn test -DupdateFixtures
   ```

   This runs all tests. Currently only `FixtureTest` supports fixture updates; other tests run normally. To run only the lexer tests in update mode:

   ```bash
   mvn test -Dtest=FixtureTest -DupdateFixtures
   ```

3. Review the generated output in the source fixtures, for example with `git diff`. Update mode writes the lexer's actual output instead of asserting that it is correct, so check the results before accepting them.
4. Run `mvn test` again without the update flag to assert against the reviewed output.

Update mode adds missing separators and expected output, preserves the source input (including trailing newlines) after normalizing CRLF to LF, and writes to `src/test/resources/fixtures`, not the build output directory. Lexer and parser exceptions are written as expected output. Repeated updates do not add newlines to the input. Normal test runs require generated expectations and do not modify fixtures.

## Running a subset of fixtures

The `testSubset` flag is intended to use the contents of `src/test/resources/testSubsetGrep.properties` as a grep pattern to select a subset of fixtures. Put the desired pattern in that file, then run:

```bash
mvn test -DtestSubset
```

Combine it with `updateFixtures` to update the selected fixtures:

```bash
mvn test -DupdateFixtures -DtestSubset
```

These boolean Maven flags do not need `=true`. Without `-DtestSubset`, all fixtures run.

## Format Java code

```bash
mvn -N -q spotless:check
mvn -N -q spotless:apply
```

## For consideration

### Lambda syntax

`() => 0` and `() => {}` works fine, but the example with the braces doesn't really need the arrow. It's only really there for the parser to identify this as a lambda. It also needs to lookahead to do this. A prefix would be simpler. eg: `fn() => 0` and `fn() {}`
