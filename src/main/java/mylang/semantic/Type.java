package mylang.semantic;

public sealed interface Type permits BuiltinType, ArrayType, FunctionType {
}
