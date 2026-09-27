# Add enum to Eld

Closely following java's implementation with some limitations.

Simple

```
enum Direction {
    NORTH,
    SOUTH,
    EAST,
    WEST
}
```

Supports constructor, methods ant fields. Constructor can only be private. Method and fields are allowed to have visibility modifiers.

```
enum Status {
    PENDING("Pending"),
    SUCCESS("Success"),
    ERROR("Error");

    public const label: string;

    Status(label: string) {
        this.label = label;
    }

    public func isComplete() bool {
        return this == SUCCESS || this == ERROR;
    }
}
```

Enum can implement interfaces

```
enum Operation implements Calculator {
ADD,
SUBTRACT;
    public func calculate(a: i32, b: i32) i32 {
        ...
    }
}
```
