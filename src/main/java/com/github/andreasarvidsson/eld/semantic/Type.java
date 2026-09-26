package com.github.andreasarvidsson.eld.semantic;

public sealed interface Type permits BuiltinFunctionType, BuiltinType,
    ArrayType, FunctionType, ClassType, UnionType, TupleType, InterfaceType,
    PromiseType, PromiseSourceType, ConstType, LiteralType {
}
