package mylang.semantic;

public record ArrayType(Type elementType) implements Type {
    @Override
    public String toString() {
        return String.format("ArrayType<%s>", elementType);
    }
}
