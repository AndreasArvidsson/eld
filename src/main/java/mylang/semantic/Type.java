package mylang.semantic;

public sealed interface Type permits BuiltinFunctionType, BuiltinType,
    ArrayType, FunctionType, ClassType {
}
