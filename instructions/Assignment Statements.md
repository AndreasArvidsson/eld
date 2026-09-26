# Assignment Statements and Postfix Increment/Decrement Expressions

Change Eld so that **assignment is a statement, not an expression**.

Postfix increment and decrement remain expressions.

The fundamental distinction is:

```text
Assignments perform mutation but do not produce values.

Postfix ++ and -- perform mutation and also produce the operand's previous value.
```

This deliberately prevents assignment from being embedded inside arbitrary expressions while retaining useful and conventional postfix operations such as:

```eld
const item = values[index++];
```

## Assignment Is a Statement

These are valid:

```eld
value = 10;
value += 5;
value -= 5;
value *= 5;
value /= 5;
```

Assignment operators must not produce expression values.

Therefore these are invalid:

```eld
const x = value = 10;
const x = value += 10;

foo(value = 10);

if (value = 10) {}

if ((value = getValue()) != null) {}

return value = 10;
```

The parser should reject assignment syntax when an expression is required.

## Motivation

Assignment expressions make it easy to accidentally perform mutation inside otherwise value-oriented expressions.

For example:

```eld
if (value != null && (value = null) == null) {
}
```

Eld should not need special semantic rules to prohibit suspicious cases like this.

Instead, the language grammar should make the entire construct impossible because:

```eld
value = null
```

is an assignment statement and therefore cannot appear as an operand to `==`, `&&`, or any other expression operator.

This also means assignment operators do not belong in the general expression precedence hierarchy.

## Simple Assignment

Support normal assignment statements:

```eld
value = 10;
name = "foo";
flag = true;
```

The left-hand side must be an assignable mutable location.

Examples of potentially valid assignment targets:

```eld
value = 10;
object.value = 10;
values[index] = 10;
```

Apply the existing Eld mutability and assignability rules.

For example, assigning to a `const` remains invalid.

## Compound Assignment

Compound assignment follows the same rule.

Support compound assignment as statements:

```eld
value += 1;
value -= 1;
value *= 2;
value /= 2;
```

They are not expressions.

Therefore:

```eld
const x = value += 1;
foo(value += 1);
return value += 1;
```

must be invalid.

Support whichever compound assignment operators Eld currently defines. Do not add unrelated operators merely as part of this change.

## Postfix Increment and Decrement Remain Expressions

Unlike assignment, postfix increment and decrement are expressions:

```eld
value++;
value--;
```

Their value is the value of the operand **before** mutation.

For example:

```eld
var value = 5;
const previous = value++;
print(previous);
print(value);
```

should output:

```text
5
6
```

Likewise:

```eld
var value = 5;
const previous = value--;
```

results in:

```text
previous = 5
value = 4
```

## Motivation for Keeping Postfix as an Expression

A common and useful pattern is consuming an indexed value while advancing the index:

```eld
const item = values[index++];
```

This should remain supported.

It is equivalent conceptually to:

```eld
const item = values[index];
index += 1;
```

but the postfix form is concise and familiar.

Preventing assignment expressions does not require preventing all mutating expressions.

The intended distinction is:

```text
value = x
    mutation statement
    no resulting value

value += x
    mutation statement
    no resulting value

value++
    mutating expression
    result = previous value

value--
    mutating expression
    result = previous value
```

## Valid Postfix Operands

The operand of postfix `++` or `--` must be an assignable mutable location.

Examples:

```eld
index++;
object.counter++;
values[index]++;
```

These should be invalid:

```eld
5++;
(a + b)++;
getValue()++;
```

Also reject postfix mutation of immutable locations:

```eld
const value = 5;
value++;
```

Use the existing l-value/mutability machinery where possible rather than introducing separate rules specifically for postfix expressions.

## Postfix Result Type

The result type of a postfix expression is the type of the operand before mutation.

For example:

```eld
var value: i32 = 5;
const previous = value++;
```

should result in:

```text
value: i32
previous: i32
```

The same principle should apply to other numeric types for which increment/decrement is supported.

Do not make postfix expressions `void`.

Their ability to produce the previous value is intentional.

## No Prefix Increment/Decrement

Continue Eld's existing decision to support postfix:

```eld
value++;
value--;
```

but not prefix:

```eld
++value;
--value;
```

Do not add prefix increment/decrement as part of this change.

This keeps only the form needed for patterns such as:

```eld
values[index++]
```

and avoids having two similar operators with different result-value semantics.

## Expression Statements

Because postfix increment/decrement are expressions, they may also appear as expression statements:

```eld
index++;
```

The resulting old value is simply discarded.

This is valid in the same way that other expressions permitted as expression statements may have unused results.

## C-Style `for` Loops

Preserve existing C-style loop syntax:

```eld
for (var i = 0; i < 10; i++) {
}
```

Because postfix `++` is an expression, it naturally works in the update position.

Compound assignment should also be supported in the update position:

```eld
for (var i = 0; i < 10; i += 2) {
}
```

Even though:

```eld
i += 2
```

is an assignment statement rather than an expression, the `for` grammar should explicitly allow the appropriate assignment/update statement form in its update position without requiring the normal trailing semicolon.

Conceptually, the grammar can treat the update portion as accepting appropriate update operations rather than requiring every update operation to be a general expression.

Do not turn compound assignment back into an expression merely to support `for`.

## Parser Design

Remove assignment operators from the general expression grammar.

For example, expression parsing should no longer recognize:

```text
=
+=
-=
*=
/=
```

as binary/expression operators.

Instead, recognize assignment at the statement level.

Conceptually:

```text
statement
    -> variableDeclaration
    -> assignmentStatement
    -> expressionStatement
    -> ...
```

The exact grammar should follow the existing parser architecture.

Postfix operators remain part of expression parsing:

```text
postfixExpression
    -> ...
    -> postfixExpression ++
    -> postfixExpression --
```

Do not change existing names or AST structures unnecessarily.

## AST

Assignments should have statement AST nodes rather than expression AST nodes.

Use the existing assignment statement representation if one already exists.

Conceptually:

```text
AssignmentStatement
  target
  operator
  value
```

where `operator` can represent:

```text
=
+=
-=
*=
/=
```

as appropriate.

Postfix increment/decrement remain expressions.

Conceptually:

```text
PostfixExpression
  operand
  operator: ++
```

Again, preserve existing AST names and structures wherever possible.

The important invariant is:

```text
AssignmentStatement instanceof Statement

PostfixExpression instanceof Expression
```

There should not be an `AssignmentExpression`.

## Semantic Analysis

Assignment statements should:

1. Resolve the assignment target.
2. Verify that it is assignable.
3. Verify that it is mutable.
4. Resolve the right-hand expression.
5. Check type compatibility/conversion.
6. Perform no expression-type registration for the assignment itself.

The right-hand expression still has its normal expression type.

Postfix expressions should:

1. Resolve the operand.
2. Verify that it is assignable.
3. Verify that it is mutable.
4. Verify that its type supports the operation.
5. Record the postfix expression's result type as the operand's value type.

## Bytecode Semantics for Postfix

Postfix operations used as values must preserve the original value before performing the mutation.

For:

```eld
var index = 5;
const previous = index++;
```

the generated behavior must be equivalent to:

```text
previous = index
index = index + 1
```

not:

```text
index = index + 1
previous = index
```

When the postfix expression's result is unused:

```eld
index++;
```

the compiler may generate simpler bytecode that does not preserve the previous value beyond what is required to perform the mutation.

Correctness takes priority over optimization.

Postfix operations on fields or indexed locations may require different JVM stack manipulation from local-variable postfix operations. Implement these through the existing l-value abstraction if one exists.

## Examples

### Valid

```eld
var value = 1;

value = 2;
value += 3;
value -= 1;

const old = value++;

print(old);
print(value);
```

### Valid indexed consumption

```eld
var index = 0;

const first = values[index++];
const second = values[index++];
```

### Valid mutation of indexed value

Assuming the collection/index operation produces an assignable mutable location:

```eld
values[index]++;
```

### Invalid assignment expression

```eld
const result = value = 10;
```

### Invalid compound assignment expression

```eld
const result = value += 10;
```

### Invalid assignment in condition

```eld
if ((value = 10) == 10) {
}
```

### Invalid assignment as argument

```eld
foo(value = 10);
```

### Valid postfix in expression

```eld
foo(index++);
```

### Valid postfix inside another expression

```eld
const item = values[index++];
```

### Invalid postfix target

```eld
const result = (a + b)++;
```

### Invalid immutable postfix target

```eld
const value = 5;
value++;
```

## Tests

Add parser, semantic, bytecode, and output tests covering at least:

- Simple `=` assignment statement.
- Existing compound assignment operators.
- Assignment to locals.
- Assignment to fields/member locations if currently supported.
- Assignment to indexed locations if currently supported.
- Assignment to immutable locations fails.
- Type-incompatible assignment fails.
- Assignment cannot occur in a variable initializer.
- Assignment cannot occur as a function argument.
- Assignment cannot occur inside a condition.
- Assignment cannot occur as a return expression.
- Compound assignment cannot occur in expression position.
- `value++` works as an expression statement.
- `value--` works as an expression statement.
- `const old = value++;` returns the previous value.
- `const old = value--;` returns the previous value.
- `values[index++]` works and increments the index exactly once.
- Postfix cannot operate on non-assignable expressions.
- Postfix cannot mutate a `const`.
- C-style `for (...; ...; i++)` continues to work.
- C-style `for (...; ...; i += 2)` continues to work.
- Prefix `++value` and `--value` remain unsupported.

Include regression tests ensuring assignment operators are no longer accepted by the general expression parser.

## Language Rule Summary

The intended Eld rules are:

```text
=    assignment statement
+=   compound assignment statement
-=   compound assignment statement
*=   compound assignment statement
/=   compound assignment statement

x++  postfix mutating expression returning old value
x--  postfix mutating expression returning old value

++x  unsupported
--x  unsupported
```

The central language-design principle is:

> Assignment is an explicit mutation statement and does not produce a value. Postfix increment/decrement are deliberately retained as mutating expressions because their previous-value semantics are useful in expressions such as `values[index++]`.

Do not add special-case prohibitions such as specifically rejecting assignment inside `if` conditions. Such constructs should already be impossible because assignment is not part of the expression grammar.
