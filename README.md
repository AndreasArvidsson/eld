# Eld

Eld (Swedish for “fire”) is a statically typed programming language that compiles to JVM bytecode.

Eld requires Java 21 or later. See [CONTRIBUTING.md](./CONTRIBUTING.md) for
building from source, development setup, tests, and implementation notes.

See [cli.md](./cli.md) for command-line and REPL usage.

## Declarations

```text
const answer = 42;
var count: i32 = 1;

func add(a: i32, b: i32) i32 {
    return a + b;
}

func log(value: any) {
    print(value);
    return;
}

class Counter {
    var value = 0;
    func next() i32 {
        return this.value++;
    }
}
```

`const` prevents reassignment; `var` allows it. Arrays and class instances can
still be mutated through a `const` binding.

## Types and literals

```text
const byte: i8 = 127;
const short: i16 = 32_767;
const int: i32 = 1_000;
const long: i64 = 9_000_000_000;
const float: f32 = 0.5;
const double: f64 = 1.25;
const enabled: bool = true;
const letter: char = 'x';
const text: string = "hello \"world\"";
const optional: i32 | null = null;
var poly: i32 | string = 5;
poly = "hello";
var unknown: any = 42;
unknown = "world";
```

Strings decode only `\"` and `\\`; other escapes such as `\n` remain literal.
Multiline strings can contain actual newlines. Character literals also support
escapes such as `'\n'` and `'\t'`.

## Arrays, slices, and tuples

```text
const values: [i32] = [1, 2, 3];
values[0] = 4;
print(values[-1]);
print(values[0:2]);
print(values[:2]);
print(values[1:]);
print(values[:]);
const nested = [[1, 2], [3, 4]];
const mixed: [any] = [1, "two", true, null];
const nullable: [i32 | null] = [1, null];
const pair: (i32, string) = (1, "one");
print(pair[0]);
print(pair == (1, "one"));
```

## Calls and function references

```text
add(1, 2);
add(b=2, a=1);
add(1, b=2);
const callback = add;
callback(1, 2);
```

Positional arguments must precede named arguments. Named arguments require a
declared function or method; calls through stored function references are positional.

## Classes and method references

```text
const counter = new Counter(0);
print(counter.value);
counter.value = 5;
print(counter.next()); // 5; value becomes 6
const next = counter.next;
print(next()); // 6; bound to counter
```

## Statements

```text
count = 3;
count++;
count--;
if (count > 0) {
    print("positive");
}
elif (count == 0) {
    print("zero");
}
else {
    print("negative");
}
while (count > 0) {
    count--;
}
do {
    count++;
} while (count < 2);
for (var i = 0; i < 3; i++) {
    if (i == 0) {
        continue;
    }
    if (i == 2) {
        break;
    }
    print(i);
}
for (value : values) {
    print(value);
}
for (value, index : values) {
    print(index);
    print(value);
}
switch (count) {
    case 1 => print("one")
    else => print("other")
}
```

## Expressions

```text
print((1 + 2) * 3 - 4 / 2 % 2);
print(count++);
print(count--);
print(!enabled);
print(count == 2 && count != 0 || false);
print(count < 3 && count <= 2 && count > 0 && count >= 1);
print("hello " + "world");
const choice = enabled ? 1 : 0;
const conditional = if (enabled) {
    yield 1;
} else {
    yield 0;
};
const selected = switch (count) {
    case 1, 2 => 10
    else => 0
};
```

1. When used as values, `if` and `switch` require an `else` and a value from
   every branch. Blocks produce values with `yield`; a switch's `=>` expression
   supplies its value directly.
2. When used as statements, `if` and `switch` can omit `else` and do not need
   to produce values.
3. Branch values must have compatible types or an explicit target type such
   as `any` or a union.

### Operators

Precedence, highest to lowest:

| Operators                            | Purpose                                              |
| ------------------------------------ | ---------------------------------------------------- |
| `()`, `[]`, `.`, postfix `++` / `--` | Calls, indexing, member access, increment/decrement. |
| Unary `+`, `-`, `!`                  | Sign and logical negation.                           |
| `*`, `/`, `%`                        | Multiplication, division, remainder.                 |
| `+`, `-`                             | Addition, string concatenation, subtraction.         |
| `<`, `<=`, `>`, `>=`                 | Ordering.                                            |
| `==`, `!=`                           | Equality.                                            |
| `&&`                                 | Logical AND.                                         |
| `\|\|`                               | Logical OR.                                          |
| `? :`                                | Conditional expression.                              |
| `=`                                  | Assignment.                                          |

1. Binary operators associate left to right; assignment and `? :` associate
   right to left. Parentheses override precedence.
2. `&&` and `||` short-circuit: the right operand runs only when needed.
   Conditional expressions evaluate only the selected branch.
3. Postfix `++` and `--` mutate the operand and return its previous value.

## Comments

```text
// Single-line comment
/* Block comment */
```

## Type reference

| Type                      | Description                                                       |
| ------------------------- | ----------------------------------------------------------------- |
| `i8`, `i16`, `i32`, `i64` | Signed integers of 8, 16, 32, and 64 bits.                        |
| `f32`, `f64`              | IEEE 754 single- and double-precision floating point.             |
| `bool`                    | `true` or `false`.                                                |
| `char`                    | A character, such as `'x'`.                                       |
| `string`                  | Text, such as `"hello"`.                                          |
| `null`                    | The null value.                                                   |
| `any`                     | Any value, including `null`; represented by Java's `Object`.      |
| `[T]`                     | A mutable array of elements of type `T`.                          |
| `(T, U)`                  | A tuple of two or more elements, which may have different types.  |
| `T \| U`                  | A union accepting either type; `T \| null` makes a type nullable. |
| `Foo`                     | An instance of a declared class `Foo`.                            |

### Numeric rules

1. Integer literals infer `i32` when they fit, otherwise `i64`. An explicit
   integer type accepts signed literals within its range; out-of-range values
   are rejected.
2. Decimal literals infer `f64`. An explicit `f32` variable or parameter accepts
   decimal literals, including signed and parenthesized ones.
3. Integers widen implicitly to larger integers or floating-point types;
   `f32` widens to `f64`. Other narrowing conversions are rejected. Conversion
   to floating point may lose precision.
4. Arithmetic and numeric comparisons promote operands to the first applicable
   type: `f64`, `f32`, `i64`, then `i32`. Unary `+` and `-` also promote `i8`,
   `i16`, and `char` to `i32`.
5. `++` and `--` retain the operand's type. Integer arithmetic wraps at the
   promoted width; increments and decrements wrap at the operand's width.

### Any and union types

| Rule           | `any`                                      | `T \| U`                                                                          |
| -------------- | ------------------------------------------ | --------------------------------------------------------------------------------- |
| Assignment     | Accepts every value.                       | Accepts members and unions containing a subset of its members.                    |
| Representation | Boxes primitives; preserves references.    | Boxes primitive members; preserves references.                                    |
| Equality       | Compares contained values using `equals`.  | Compares contained values using `equals`; `i32` and `i64` values remain distinct. |
| Narrowing      | No implicit conversion to a concrete type. | No implicit or flow-sensitive narrowing to a member.                              |

1. `any` cannot be used for arithmetic, conditions, calls, or indexing. Union
   arithmetic requires a statically numeric type.
2. Union member order and duplicates do not matter; a union containing `any`
   simplifies to `any`.
3. Unions work in parameters, return types, and array elements. `[i32 | null]`
   is an array of nullable integers; `[i32] | null` is a nullable array.
4. Mixed array literals need an explicit union or `any` element type. Unrelated
   branch types do not automatically infer a union.

### Arrays and tuples

| Behavior      | Arrays                                                | Tuples                                                                 |
| ------------- | ----------------------------------------------------- | ---------------------------------------------------------------------- |
| Indexing      | Integer indices; negative indices count from the end. | Nonnegative integer literal indices.                                   |
| Invalid index | Rejected at runtime.                                  | Rejected at compile time.                                              |
| Mutation      | Elements can be reassigned.                           | Elements cannot be reassigned; contained arrays can still be modified. |
| Printing      | `[1, 2, 3]`                                           | `(42, answer)`                                                         |

1. Arrays are invariant: `[i32]` cannot be assigned to `[any]` or `[i32 | null]`.
2. Tuple types can be inferred and nested. Literal elements use the same
   conversions as variable initializers. A `var` tuple can be reassigned.
3. Array slices copy elements from an inclusive start to an exclusive end.
   Omitted bounds default to zero and the array length. After negative bounds
   are normalized, both must be within `0..length`; invalid or reversed ranges
   throw.
4. Source-level array append syntax is not yet implemented.
