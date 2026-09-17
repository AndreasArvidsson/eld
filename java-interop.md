# Java Interoperability

The language should provide a deliberate Java interoperability layer
rather than requiring Java APIs to be wrapped manually in the standard
library.

Because the compiler targets JVM bytecode directly, invoking Java code
at runtime is relatively straightforward. The main work belongs in
semantic analysis: Java classes, constructors, methods, and fields need
to be exposed as symbols that can participate in normal name resolution
and type checking.

## Goals

Java interoperability should eventually support:

-   importing Java classes;
-   constructing Java objects;
-   calling static and instance methods;
-   accessing public fields and constants;
-   overloaded methods;
-   external JARs on the compiler classpath;
-   conversion between Java primitive types and built-in language types;
-   gradual support for more complicated Java features such as generics
    and varargs.

Java APIs should not be represented as compiler builtins. Builtins such
as `print` can retain special compiler semantics, while ordinary Java
APIs should be represented through dedicated Java interop symbols and
compile to ordinary JVM invocation instructions.

## Importing Java classes

Java classes should be directly importable.

For example:

``` eld
import java.time.LocalDate;
import java.util.UUID;

const today = LocalDate.now();
const id = UUID.randomUUID();

print(today);
```

Aliasing should also be supported where Java class names conflict:

``` eld
import java.util.Date as UtilDate;
import java.sql.Date as SqlDate;
```

The exact import syntax can still be aligned with the language's general
module/import design. The important semantic property is that the
imported name resolves to a Java-backed class symbol.

For example:

``` text
LocalDate
    ↓
JavaClassSymbol(java.time.LocalDate)
```

## Java symbols

The semantic model should represent Java declarations explicitly rather
than treating them as builtins.

Potential symbol types include:

``` text
JavaClassSymbol
JavaMethodSymbol
JavaFieldSymbol
JavaConstructorSymbol
```

For example:

``` eld
LocalDate.now();
```

could resolve to:

``` text
LocalDate
    ↓
JavaClassSymbol(java.time.LocalDate)

now
    ↓
JavaMethodSymbol(
    owner=java.time.LocalDate,
    name=now,
    parameterTypes=[],
    returnType=java.time.LocalDate,
    static=true
)
```

The bytecode generator then receives an already resolved Java method and
only needs to emit the appropriate JVM instruction:

``` text
INVOKESTATIC java/time/LocalDate.now ()Ljava/time/LocalDate;
```

This follows the existing compiler architecture: resolution and type
checking happen during semantic analysis rather than being rediscovered
during bytecode generation.

## Discovering Java APIs

Java reflection can be used at compile time to discover available APIs.

For example:

``` java
Class<?> clazz = Class.forName("java.time.LocalDate");
```

The compiler can inspect:

``` java
clazz.getMethods();
clazz.getConstructors();
clazz.getFields();
```

and construct semantic symbols from the results.

This avoids maintaining a hardcoded model of the JDK. The Java APIs
visible to the compiler can instead correspond to the JDK and classpath
against which the program is being compiled.

External JARs can be made available through an appropriate compiler
classpath and `ClassLoader`.

## External libraries

Initially, dependency resolution does not need to be part of the
language or compiler.

The compiler can simply accept a classpath:

``` text
eld compile --classpath some-library.jar app.eld
```

Code can then import classes from that library normally.

For example:

``` eld
import com.fasterxml.jackson.databind.ObjectMapper;

const mapper = new ObjectMapper();
```

The compiler resolves `ObjectMapper` from the supplied classpath,
discovers its public API, type-checks calls against it, and generates
ordinary JVM bytecode.

The same dependencies must be available on the runtime classpath.

Build-tool integration can later supply this classpath automatically.

## Primitive type mapping

Java primitive types should map directly to the corresponding built-in
types.

  Java        Language
  ----------- ----------
  `byte`      `i8`
  `short`     `i16`
  `int`       `i32`
  `long`      `i64`
  `float`     `f32`
  `double`    `f64`
  `boolean`   `bool`
  `char`      `char`
  `String`    `string`
  `void`      `void`

Other Java classes become Java-backed class types.

The JVM descriptors then follow naturally:

``` text
i8  → B
i16 → S
i32 → I
i64 → J
f32 → F
f64 → D
```

## Java arrays

Java arrays should not automatically be treated as ordinary language
arrays if the language's `[T]` type represents a growable collection.

For example, these are semantically different concepts:

``` text
Java int[]     fixed-size JVM array
Language [i32] growable language collection
```

The initial Java interop implementation can postpone automatic
conversion between these representations.

Later possibilities include:

-   an explicit Java-array interop type;
-   boundary conversions between Java arrays and language arrays;
-   specialized compiler handling where appropriate.

The source-language collection model should not be constrained by the
JVM array model.

## Constructors

Java construction should use the same source syntax as ordinary object
construction:

``` eld
import java.util.Random;

const random = new Random();
```

Semantic analysis resolves the constructor, and bytecode generation
emits normal JVM construction:

``` text
NEW java/util/Random
DUP
INVOKESPECIAL java/util/Random.<init> ()V
```

## Static fields and constants

Static Java fields should be accessible naturally.

For example:

``` eld
import java.lang.Math;

const pi = Math.PI;
```

can compile to:

``` text
GETSTATIC java/lang/Math.PI : D
```

and `pi` has type `f64`.

## Static methods

Static methods should behave like ordinary callable members.

For example:

``` eld
import java.lang.Math;

const result = Math.sqrt(10.0);
```

can resolve to Java's:

``` text
Math.sqrt(f64) → f64
```

and compile to:

``` text
LDC 10.0D
INVOKESTATIC java/lang/Math.sqrt (D)D
```

## Instance methods

Instance methods work similarly.

For example:

``` eld
import java.lang.StringBuilder;

const builder = new StringBuilder();
builder.append("Hello");
builder.append(" world");

print(builder.toString());
```

The semantic analyzer resolves each method against the Java-backed type
of `builder`, and the backend emits the appropriate invocation
instructions.

## Overload resolution

Java overloads are one of the more substantial semantic requirements.

A Java class may expose:

``` java
foo(int value)
foo(long value)
foo(String value)
foo(Object value)
```

For:

``` eld
foo(10);
```

the compiler needs to select the appropriate overload.

The first implementation does not need to reproduce every detail of
`javac` overload resolution. A simpler deterministic model can initially
be used:

1.  Find methods with the requested name.
2.  Filter by arity.
3.  Keep candidates whose parameter types can accept the argument types.
4.  Prefer exact type matches.
5.  Then prefer safe widening conversions.
6.  Report an ambiguity if multiple equally good candidates remain.

For an `i32` argument, `foo(int)` should therefore be preferred over
`foo(long)`.

More advanced behavior can be added later.

## Generics

Full Java generic type support should not be required for the first
interop implementation.

Java generics include substantial complexity:

``` java
Map<String, List<? extends Comparable<? super Foo>>>
```

Initial interoperability can focus on:

-   classes;
-   constructors;
-   public fields;
-   static methods;
-   instance methods;
-   primitive parameters and return values;
-   ordinary object parameters and return values;
-   straightforward overload resolution.

Generics can later be incorporated into the semantic model as additional
type information.

## Varargs and other Java features

Features such as these can also be postponed:

-   varargs;
-   generic methods;
-   wildcards;
-   annotations;
-   checked exceptions;
-   reflection-specific behavior;
-   complicated bridge/synthetic methods.

The initial goal should be useful access to ordinary Java APIs, not
perfect source-level compatibility with every Java language feature.

## Builtins versus Java interop

Compiler builtins and Java interop should remain distinct concepts.

For example:

``` text
print
    ↓
BuiltinFunctionType(print)
```

is appropriate because `print` has special language/compiler semantics.

By contrast:

``` eld
Math.sqrt(25.0)
```

should resolve through normal Java interop:

``` text
JavaClassSymbol
    ↓
JavaMethodSymbol
    ↓
INVOKESTATIC
```

Java methods should not gradually become a growing collection of special
compiler builtins.

## Suggested first implementation

A good first interoperability fixture is `Math.sqrt`:

``` eld
import java.lang.Math;

const value = Math.sqrt(25.0);
print(value);
```

It exercises:

-   importing a Java class;
-   resolving a static method;
-   primitive type mapping;
-   overload resolution;
-   a Java return value;
-   JVM invocation generation.

The important semantic resolution is approximately:

``` text
Math
    → JavaClassSymbol(java.lang.Math)

Math.sqrt
    → JavaMethodSymbol(
        owner=java.lang.Math,
        name=sqrt,
        parameterTypes=[f64],
        returnType=f64,
        static=true
    )
```

with bytecode:

``` text
LDC 25.0D
INVOKESTATIC java/lang/Math.sqrt (D)D
```

A useful second fixture is an instance API:

``` eld
import java.lang.StringBuilder;

const builder = new StringBuilder();
builder.append("Hello");
builder.append(" world");

print(builder.toString());
```

Once static calls, construction, and instance calls work, the compiler
has the foundation for useful access to the wider Java ecosystem.

## Design principle

Java interoperability should be an escape hatch into the JVM ecosystem
without making Java's source-language design part of the language
itself.

The compiler can leverage Java classes, reflection, JVM descriptors, and
bytecode instructions internally while exposing its own type system and
language semantics.

In particular:

-   Java `Pattern` can still appear as the implementation of the
    language's `regex` type;
-   Java fixed-size arrays do not have to define the semantics of
    language `[T]` collections;
-   Java `Object` does not necessarily have to become a source-visible
    universal base class;
-   Java methods can be called directly without being manually wrapped
    as builtins.

The JVM is the implementation platform, not the language specification.
