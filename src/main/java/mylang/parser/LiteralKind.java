package mylang.parser;

public enum LiteralKind {
    INT("int"),
    FLOAT("float"),
    BOOL("bool"),
    CHAR("char"),
    STRING("string"),
    NULL("null");

    private final String name;

    private LiteralKind(final String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
