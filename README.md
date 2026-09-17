# Eld

Run these commands from the project root with Java 21 or later and Maven 3.9 or later.

## Command line and REPL

Build the executable application, then use the launcher in Git Bash:

```bash
mvn package
./bin.sh compile foo.eld
./bin.sh run foo.eld
./bin.sh repl
```

PowerShell and Command Prompt can use `bin.cmd` (for example, `./bin.cmd repl`).
You can also run `java -jar target/eld-1.0-SNAPSHOT.jar repl`.
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
eld> var count = 1;
eld> func next() i32 { count = count + 1; return count; }
eld> next();
2
eld> count;
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

Comments use `//` to the end of the line or `/* ... */` across lines.
They act as whitespace between tokens. Block comments do not nest; an
unterminated block comment is a lexer error. Comment markers inside string
and character literals remain literal text.

## Numeric types

The signed integer types are `i8`, `i16`, `i32`, and `i64` (8, 16, 32, and
64 bits). The floating-point types are `f32` and `f64` (IEEE 754 single and
double precision). `i32` replaces `int`, and `f32` replaces `float`; the old
names are no longer accepted as types.

```text
var small: i8 = 127;
var count: i64 = 9000000000;
var ratio: f32 = 0.5;
var precise: f64 = 1.23456789012345;
func twice(value: i64) i64 { return value * 2; }
```

Integer literals infer `i32` when they fit, otherwise `i64`. Decimal literals
infer `f64`; an explicit `f32` variable or parameter type converts the
`f64` literal to `f32` (including signed and parenthesized literals). Signed integer literals can initialize any integer size that can
hold their value. Out-of-range literals are rejected.

Integer values widen implicitly to larger integers or floating-point types;
`f32` also widens to `f64`. Other narrowing conversions are rejected. Arithmetic and numeric comparisons follow Java's binary numeric promotion:
use `f64` if either operand is `f64`, otherwise `f32`, otherwise `i64`,
otherwise `i32`. This includes `char`: operations on `i8`, `i16`, and `char`
produce `i32`, even when both operands have the same small type. Unary `+`
and `-` also promote these types to `i32`. Increments and decrements retain
the operand's type. Integer arithmetic wraps at the promoted width; increments
and decrements wrap at the operand's width. Widening to floating point can
lose precision, just as in Java.

## Tuples

Tuples use parentheses for both types and values, with two or more elements.
Elements can have different types, including nested tuples and arrays.

```text
const pair: (i64, string) = (42, "answer");
print(pair); // (42, answer)
print(pair[0]); // 42
func point() (f64, f64) { return (1.0, 2.0); }
```

Tuple types can be inferred. Literal elements support the same conversions as
variable initializers. Access uses a nonnegative integer literal index; invalid
indices are rejected at compile time. Tuple elements cannot be reassigned, but
mutable arrays stored inside tuples can still be modified. Tuple variables can
be reassigned when declared with `var`.

## Array runtime

Union types use `|`, for example `var value: i32 | null = null;`.
A member value can be assigned to its union, and a union can widen to another
union containing all its members. Member order and duplicates do not change
type identity. Primitive members are boxed at union boundaries; reference
members retain their runtime representation. Equality compares boxed values
with their runtime types, so an `i32` member and an `i64` member remain distinct.
Unions work in parameters, return types, and array elements such as
`[i32 | null]`; `[i32] | null` instead describes an optional array.
Nullable unions use the member's reference representation: `string | null`
uses `String`, `[i32] | null` uses `EldIntArray`, and `i32 | null` uses
`Integer`. Unions with multiple distinct non-null members use `Object`.
Mutable arrays remain invariant. Union values cannot implicitly narrow to one
member, and flow-sensitive narrowing is not implemented. Arithmetic still
requires a statically numeric type. Heterogeneous array literals require an
explicit union element type; unrelated branch types are not automatically
combined into inferred unions.

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

Subscripting accepts negative indices relative to the end and rejects
out-of-range indices. Slices use an inclusive start and exclusive end
and return independent copies. Omitted bounds default to zero and the
logical length. For all arrays, normalized slice bounds must be between zero
and the logical length (inclusive); invalid bounds and reversed ranges throw.

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

### Lambda syntax

`() => 0` and `() => {}` works fine, but the example with the braces doesn't really need the arrow. It's only really there for the parser to identify this as a lambda. It also needs to lookahead to do this. A prefix would be simpler. eg: `fn() => 0` and `fn() {}`

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

- Optional arguments: `foo?: i32`
- Default argument values: `foo: i32 = 0`
- overloaded functions
- Ubiquitous class toString and equals methods.
- Regular expressions from `regex`. Also add literals `const re: regex = /^\d+$`;
- To be wont to support operation overloading? eg defining add/mult etc for custom classes.
- Class variables and methods are private by default. Only need the public modifier.
