# Command line and REPL

After [building the application](CONTRIBUTING.md#building), use the launcher
from the project root in Git Bash:

```bash
./bin.sh compile foo.eld
./bin.sh run foo.eld
./bin.sh repl
```

PowerShell and Command Prompt can use `bin.cmd` (for example, `./bin.cmd repl`).
You can also run `java -jar target/eld-1.0-SNAPSHOT.jar repl`.

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
