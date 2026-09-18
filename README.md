# Eld

Eld is a statically typed programming language that compiles to JVM bytecode.

Eld requires Java 21 or later. See [CONTRIBUTING.md](CONTRIBUTING.md) for
building from source, development setup, tests, and implementation notes.

See [cli.md](cli.md) for command-line and REPL usage.

## Declarations

```text
const answer = 42;
var count: i32 = 1;

func add(a: i32, b: i32) i32 {
    return a + b;
}

func log(value: any) {
    print(value); return;
}

class Counter {
    var value = 0;
    func next() i32 {
        return value++;
    }
}
```

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
const text: string = "hello\nworld";
const optional: i32 | null = null;
var poly: i32 | string = 5;
poly = "hello";
var unknown: any = 42;
unknown = "world";
```

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

## Statements

```text
count = 3;
count++;
count--;
if (count > 0) { print("positive"); }
elif (count == 0) { print("zero"); }
else { print("negative"); }
while (count > 0) { count--; }
do { count++; } while (count < 2);
for (var i = 0; i < 3; i++) {
    if (i == 0) { continue; }
    if (i == 2) { break; }
    print(i);
}
for (value : numbers) { print(value); }
for (value, index : numbers) { print(index); print(value); }
switch (count) { case 1 => print("one") else => print("other") }
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

## Comments

```text
// Single-line comment
/* Block comment */
```

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

## Any type

`any` is an unknown value type represented by Java's `Object`. Every value
can be assigned to it, including `null`; primitive values are boxed.

```text
var value: any = 42;
value = "hello";
const values: [any] = [1, "two", true, null];
func identity(value: any) any { return value; }
```

An `any` value cannot implicitly narrow to a concrete type or be used for
arithmetic, conditions, calls, or indexing. Equality uses the contained values'
`equals` methods. Arrays remain invariant: an existing `[i32]` cannot be
assigned to `[any]`. A union containing `any` simplifies to `any`.

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

## Union types

Union types use `|`, for example `var value: i32 | null = null;`.
A member value can be assigned to its union, and a union can widen to another
union containing all its members. Member order and duplicates do not change
type identity. Primitive members are boxed at union boundaries; reference
members retain their runtime representation. Equality compares boxed values
with their runtime types, so an `i32` member and an `i64` member remain distinct.
Unions work in parameters, return types, and array elements such as
`[i32 | null]`; `[i32] | null` instead describes an optional array.
Mutable arrays remain invariant. Union values cannot implicitly narrow to one
member, and flow-sensitive narrowing is not implemented. Arithmetic still
requires a statically numeric type. Heterogeneous array literals require an
explicit union element type; unrelated branch types are not automatically
combined into inferred unions.

## Arrays

Arrays print their logical contents, such as `[1, 2, 3]`, `[true, false]`,
or `[h, i]`. Source-level append syntax is not yet implemented.

Subscripting accepts negative indices relative to the end and rejects
out-of-range indices. Slices use an inclusive start and exclusive end
and return independent copies. Omitted bounds default to zero and the
logical length. For all arrays, normalized slice bounds must be between zero
and the logical length (inclusive); invalid bounds and reversed ranges throw.
