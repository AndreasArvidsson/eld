# Contributing to Eld

See [README.md](README.md) for language syntax and [cli.md](cli.md) for
command-line and REPL usage.

## Development setup

Use Java 21 or later and Maven 3.9 or later. Run the commands below from the
project root.

## Building

Build the executable application and run the checks:

```bash
mvn package
```

The executable JAR is written to `target/eld-1.0-SNAPSHOT.jar`. Rebuild with
`mvn package` after changing the application. See the
[command-line and REPL instructions](cli.md) to run it.

## Array runtime

Nullable unions use the member's reference representation: `string | null`
uses `String`, `[i32] | null` uses `EldIntArray`, and `i32 | null` uses
`Integer`. Unions with multiple distinct non-null members use `Object`.

Primitive arrays use specialized growable classes in
`com.github.andreasarvidsson.eld.runtime`, with primitive backing storage
and a separate logical size. The `EldArray` base class shares size, bounds
validation, capacity management, copying and slicing logic, and formatting.
The base's self-type parameter describes the array class, not its primitive
elements, so sharing this logic requires no boxing. Thin covariant overrides
preserve specialized copy/slice return types
and let generated code call their specialized JVM descriptors directly.
Each specialization
keeps primitive storage, element access, and small hooks for copying,
resizing, and appending an element without boxing.

| Element type                 | Runtime class       | Storage     |
| ---------------------------- | ------------------- | ----------- |
| `i8`                         | `EldByteArray`      | `byte[]`    |
| `i16`                        | `EldShortArray`     | `short[]`   |
| `i32`                        | `EldIntArray`       | `int[]`     |
| `i64`                        | `EldLongArray`      | `long[]`    |
| `f32`                        | `EldFloatArray`     | `float[]`   |
| `f64`                        | `EldDoubleArray`    | `double[]`  |
| `bool`                       | `EldBooleanArray`   | `boolean[]` |
| `char`                       | `EldCharArray`      | `char[]`    |
| `string`, `null`, references | `EldObjectArray<T>` | `Object[]`  |

They print their logical contents, such as `[1, 2, 3]`, `[true, false]`,
or `[h, i]`. Each runtime class provides `add(value)` for growth;
source-level append syntax is not yet implemented. Reference and nested
arrays use `EldObjectArray<T>` and share the same base-class behavior.
Strings remain Java strings, and null is a valid element type. Reference
element reads use JVM casts where required by the Eld semantic type.
Compiled executable JARs include all array runtime classes and their base class.

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

`FixtureTest` discovers all `.fixture` files under `fixtures`, including subdirectories. Each file contains lexer input followed by a blank line and `--- TOKENS ---` (the delimiter is `\n\n--- TOKENS ---\n`). Both newlines before the header belong to the separator, not the source input. Any additional newlines before that separator remain part of the input. Windows and Unix line endings are supported.

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

Update mode adds missing separators and expected output, preserves the source input (including trailing newlines) after normalizing CRLF to LF, and writes to `fixtures`, not the build output directory. Lexer and parser exceptions are written as expected output. Repeated updates do not add newlines to the input. Normal test runs require generated expectations and do not modify fixtures.

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

Maven runs `spotless:check` during `validate`, before compilation, including
when running `mvn compile`, `mvn test`, or `mvn package`. Incremental checking
skips unchanged files that already passed. `mvn clean` removes the cache,
so the next check processes all Java files. Formatting violations fail the
build; use `spotless:apply` to fix them.

```bash
mvn -N -q spotless:check
mvn -N -q spotless:apply
```

## For consideration

### Export & import

- Remove default exports. All exports must be named.
- Imports are always named and do not use braces.
- Aliasing and wildcard imports are supported.

Valid

```ts
// foo and bar are named imports
import foo, bar from "lib"
// Aliasing is supported
import foo as bar from "lib"
// Wildcard is supported
import * as lib from "lib"

export const foo = 0;
```

Invalid

```ts
import { foo } from "lib";
// foo here is a default export
import foo from "lib";

export default foo;
```

### More default libraries should return null

- Methods like `indexOf` should return `null` instead of `-1` on failure. Force the user to deal with this scenario.

### Support Comparable interface

- Add a `Comparable` interface. Classes that implement it can be sorted without a callback.
- Non-primitive types that do not implement `Comparable` cannot be sorted without a callback.

Valid

```ts
// Foo implements Comparable
var items: Foo[];
items.sort();
items.sort((a, b) -> a.value - b.value);
```

Invalid

```ts
// Foo does not implement Comparable
var items: Foo[];
items.sort();
```

### Immutable collections and objects

Mutable by default with `readonly` keyword

| Type                      | Array mutable? | Elements mutable through array? |
| ------------------------- | -------------: | ------------------------------: |
| `[Foo]`                   |            yes |                             yes |
| `readonly [Foo]`          |             no |                             yes |
| `(readonly Foo]`          |            yes |                              no |
| `readonly [readonly Foo]` |             no |                              no |

Immutable by default with `mut` keyword

| Type            | Array mutable? | Elements mutable through array? |
| --------------- | -------------: | ------------------------------: |
| `[Foo]`         |             no |                              no |
| `mut [Foo]`     |            yes |                              no |
| `(mut Foo]`     |             no |                             yes |
| `mut [mut Foo]` |            yes |                             yes |

### Miscellaneous

- overloaded functions
- Exceptions
- Inheritance
- Ubiquitous class toString and equals methods.
- Class variables and methods are private by default. Only need the public modifier.
- Regular expressions from `regex`. Also add literals `const re: regex = /^\d+$`;
- To be wont to support operation overloading? eg defining add/mult etc for custom classes.
