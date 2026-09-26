# Add Literal Types to Eld

Implement **literal types** in Eld.

A literal type represents one specific value rather than all values of its underlying primitive type.

For example:

```eld
const value: "foo" | false | 0 = "foo";
```

The type of `value` is:

```text
"foo" | false | 0
```

It is **not**:

```text
string | bool | i32
```

## Supported Literal Types

Initially support:

- String literals
- Boolean literals
- Integer literals

Examples:

```eld
const status: "success" | "error" = "success";
const flag: true | false = true;
const result: 0 | 1 = 1;
const mixed: "foo" | false | 0 = false;
```

Do **not** add floating-point literal types yet.

## Type Relationships

A literal type is a subtype of its ordinary value type:

```text
"foo" <: string
"bar" <: string

true  <: bool
false <: bool

0  <: i32
1  <: i32
42 <: i32
```

Consequently:

```eld
const x: "foo" = "foo";
const y: string = x;
```

is valid.

Likewise:

```eld
const x: 0 | 1 = 1;
const y: i32 = x;
```

is valid.

These must fail:

```eld
const x: "foo" = "bar";
const y: false = true;
const z: 0 = 1;
```

The compiler should report an incompatible-value/type error in the normal semantic validation phase.

## Do Not Infer Literal Types for Ordinary Literals

Literal expressions should retain their existing ordinary types unless a literal type is explicitly involved.

For example:

```eld
const x = "foo";
const y = true;
const z = 5;
```

should continue to infer:

```text
x: string
y: bool
z: i32
```

Do **not** infer:

```text
x: "foo"
y: true
z: 5
```

The normal expression types should therefore remain:

```text
"foo" -> string
true  -> bool
5     -> i32
```

Literal types are primarily part of the type system, not a replacement for the existing types of literal expressions.

## Parsing

Allow supported literals wherever a type is expected.

For example:

```eld
const status: "success" | "failure" = "success";
const enabled: true = true;
const result: 0 | 1 = 0;
const mixed: "foo" | false | 0 = "foo";
```

The AST should represent literal types explicitly rather than treating them as ordinary expression nodes.

Introduce an appropriate type node, for example:

```text
LiteralTypeNode
```

or separate nodes if that better fits the existing AST architecture.

Preserve the original source representation/range in the AST, consistent with existing literal handling.

For example:

```eld
const value: "foo" | false | 0 = "foo";
```

should conceptually contain:

```text
UnionTypeNode
  LiteralTypeNode STRING "fo
```

## Testing

- Extend unit test for commonType().
- Add appropriate `.fixture` files.
