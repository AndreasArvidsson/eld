# Fix Eld member resolution for explicitly declared methods that collide with inherited Java methods

## Problem

An explicitly declared Eld instance method can currently lose overload resolution to an inherited Java method with the same source-level name.

For example:

```eld
class Foo {
    public func equals(value: any) bool {
        return true;
    }
}

const foo = new Foo();
print(foo.equals("x"));
print(foo.equals(foo));
print(foo == new Foo());
```

The current compiler behavior is effectively:

```text
foo.equals("x")
    -> Foo.$eld$equals(Object)
    -> Eld method

foo.equals(foo)
    -> Object.equals(Object)
    -> inherited Java method

foo == new Foo()
    -> reference identity
```

The first two calls are ordinary source-level calls to the same member name, but different methods are selected depending on the argument type.

The reason appears to be that member/overload resolution considers both:

```text
Foo.equals(any)       // explicitly declared Eld method
Object.equals(Object) // inherited Java method
```

and ranks `Foo -> Object` above `Foo -> any` for `foo.equals(foo)`.

This makes an explicitly declared Eld method unexpectedly lose to an inherited Java implementation.

## Desired behavior

An explicitly declared Eld member should take precedence over inherited Java members with the same source-level name.

For:

```eld
class Foo {
    public func equals(value: any) bool {
        return true;
    }
}
```

both of these should resolve to the explicitly declared Eld method:

```eld
foo.equals("x");
foo.equals(foo);
```

Conceptually, member lookup should work as:

```text
Foo declares members named "equals"
        |
        v
use Foo's declared "equals" overload set
        |
        v
perform overload resolution within that set
```

The inherited `Object.equals(Object)` should not compete merely because it provides a more specific conversion for one particular argument.

This should be a **general member-resolution rule**, not a special case for the name `equals`.

## Important: do not change Eld `==`

This change concerns ordinary member calls only.

An instance method named `equals` has no special relationship with the Eld equality operator.

For:

```eld
class Foo {
    public func equals(value: any) bool {
        return true;
    }
}
```

this:

```eld
foo == other
```

must **not** call `Foo.equals`.

Under the current equality rules, a user-defined Eld class without an Eld `equal` declaration uses reference identity.

Therefore:

```eld
const foo = new Foo();

print(foo == foo);       // true
print(foo == new Foo()); // false
```

should continue to compile to identity comparison such as `IF_ACMPEQ`.

Eld value equality is instead defined explicitly by an Eld equality declaration such as:

```eld
class Foo {
    public static func equal(a: Foo, b: Foo) bool {
        // ...
    }
}
```

Keep these concepts separate:

```text
foo.equals(...)
    -> ordinary Eld member lookup

foo == other
    -> Eld equality strategy
```

## JVM method naming

The existing JVM name mangling for an ordinary Eld method named `equals` should be preserved.

For example:

```eld
public func equals(value: any) bool
```

can continue to compile as:

```text
$eld$equals(Object)Z
```

rather than:

```text
equals(Object)Z
```

This is important because an ordinary Eld method named `equals` should not accidentally override Java's `Object.equals(Object)`.

Otherwise simply declaring:

```eld
public func equals(value: any) bool
```

could unexpectedly change Java interoperability behavior in `HashMap`, `HashSet`, `ArrayList.contains`, and other Java APIs.

Only the compiler's intentional Java interoperability bridge for actual Eld value equality should override `Object.equals`.

## Equality interoperability remains separate

The intended distinction is:

```text
Ordinary Eld method:

    func equals(...)
        -> ordinary method
        -> JVM name may be mangled
        -> does not affect ==


Eld equality declaration:

    static func equal(a: Foo, b: Foo)
        -> defines Eld == semantics
        -> compiler may generate equals(Object) bridge
          for Java interoperability


No Eld equal:

    Foo == Foo
        -> reference identity
```

Do not solve the member-resolution issue by making ordinary `equals` methods JVM overrides.

## Regression test

Add a fixture where the result makes the selected method observable:

```eld
class Foo {
    public func equals(value: any) bool {
        return false;
    }
}

const foo = new Foo();

print(foo.equals("x"));
print(foo.equals(foo));
print(foo == foo);
print(foo == new Foo());
```

Expected output:

```text
false
false
true
false
```

The important second result verifies that:

```eld
foo.equals(foo)
```

calls the explicitly declared Eld `Foo.equals(any)`, not inherited `Object.equals(Object)`.

The third and fourth results verify that the ordinary method named `equals` has not affected Eld `==`.

Expected JVM behavior should conceptually be:

```text
foo.equals("x")
    -> INVOKEVIRTUAL Foo.$eld$equals(Object)Z

foo.equals(foo)
    -> INVOKEVIRTUAL Foo.$eld$equals(Object)Z

foo == foo
    -> reference identity

foo == new Foo()
    -> reference identity
```

## Scope

Investigate the general member lookup/overload resolution logic rather than adding an `equals`-specific exception.

The intended rule is:

> If an Eld type explicitly declares members with a given source-level name, inherited Java members with that name should not compete with those declarations during ordinary member-call overload resolution.

Preserve existing overload resolution between multiple applicable methods declared in the selected Eld member set.

Also add regression coverage for a non-`equals` method name if practical, to demonstrate that the behavior is a general Eld-vs-inherited-Java member-resolution rule rather than equality-specific logic.

## Do not change

Do not change:

- Eld `==` semantics.
- Identity equality for user-defined Eld classes without `equal`.
- Built-in Eld equality strategies such as structural array equality.
- Java equality behavior for actual Java types.
- Generated Java `equals(Object)` bridges for Eld classes that explicitly define Eld value equality.
- JVM mangling that prevents ordinary Eld methods named `equals` from accidentally overriding `Object.equals`.
