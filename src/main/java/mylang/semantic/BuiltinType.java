package mylang.semantic;

public enum BuiltinType implements Type {
    INT("int"),
    FLOAT("float"),
    BOOL("bool"),
    CHAR("char"),
    STRING("string"),
    NULL("null");

    private final String name;

    private BuiltinType(final String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
