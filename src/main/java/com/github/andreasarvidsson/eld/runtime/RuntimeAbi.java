package com.github.andreasarvidsson.eld.runtime;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import java.lang.classfile.TypeKind;
import com.github.andreasarvidsson.eld.semantic.BuiltinType;
import com.github.andreasarvidsson.eld.semantic.LiteralType;
import com.github.andreasarvidsson.eld.semantic.Type;

final public class RuntimeAbi {
    public static final String EMPTY_ARRAY_CONSTRUCTOR = "()V";

    public enum ArrayKind {
        BYTE(BuiltinType.I8, EldByteArray.class, "B", TypeKind.BYTE),
        SHORT(BuiltinType.I16, EldShortArray.class, "S", TypeKind.SHORT),
        INT(BuiltinType.I32, EldIntArray.class, "I", TypeKind.INT),
        LONG(BuiltinType.I64, EldLongArray.class, "J", TypeKind.LONG),
        FLOAT(BuiltinType.F32, EldFloatArray.class, "F", TypeKind.FLOAT),
        DOUBLE(BuiltinType.F64, EldDoubleArray.class, "D", TypeKind.DOUBLE),
        BOOLEAN(BuiltinType.BOOL, EldBooleanArray.class, "Z", TypeKind.BOOLEAN),
        CHAR(BuiltinType.CHAR, EldCharArray.class, "C", TypeKind.CHAR),
        OBJECT(
            null,
            EldObjectArray.class,
            "Ljava/lang/Object;",
            TypeKind.REFERENCE
        );

        public final @Nullable BuiltinType element;
        public final Class<?> runtimeClass;
        public final String owner;
        public final String descriptor;
        public final String elementDescriptor;
        public final String backingDescriptor;
        public final String constructorDescriptor;
        public final TypeKind creationKind;

        ArrayKind(
            final @Nullable BuiltinType element,
            final Class<?> runtimeClass,
            final String elementDescriptor,
            final TypeKind creationKind
        ) {
            this.element = element;
            this.runtimeClass = runtimeClass;
            this.owner = runtimeClass.getName().replace('.', '/');
            this.descriptor = "L" + owner + ";";
            this.elementDescriptor = elementDescriptor;
            this.backingDescriptor = "[" + elementDescriptor;
            this.constructorDescriptor = "(" + backingDescriptor + ")V";
            this.creationKind = creationKind;
        }

        public String methodDescriptor(final ArrayMethod method) {
            return switch (method) {
                case SIZE -> "()I";
                case GET -> "(I)" + elementDescriptor;
                case SET -> "(I" + elementDescriptor + ")V";
                case ADD -> "(" + elementDescriptor + ")V";
                case COPY -> "()" + descriptor;
                case SLICE_FROM, SLICE_TO -> "(I)" + descriptor;
                case SLICE -> "(II)" + descriptor;
            };
        }
    }

    public enum ArrayMethod {
        SIZE("size"),
        GET("get"),
        SET("set"),
        ADD("add"),
        COPY("copy"),
        SLICE_FROM("sliceFrom"),
        SLICE_TO("sliceTo"),
        SLICE("slice");

        public final String methodName;

        ArrayMethod(final String methodName) {
            this.methodName = methodName;
        }

    }

    public static ArrayKind array(final Type element) {
        if (!(LiteralType.unwrap(element) instanceof BuiltinType builtin)) {
            return ArrayKind.OBJECT;
        }
        return switch (builtin) {
            case I8 -> ArrayKind.BYTE;
            case I16 -> ArrayKind.SHORT;
            case I32 -> ArrayKind.INT;
            case I64 -> ArrayKind.LONG;
            case F32 -> ArrayKind.FLOAT;
            case F64 -> ArrayKind.DOUBLE;
            case BOOL -> ArrayKind.BOOLEAN;
            case CHAR -> ArrayKind.CHAR;
            case STRING, NULL, ANY -> ArrayKind.OBJECT;
            case VOID -> throw new IllegalArgumentException(
                "void is not an array element type"
            );
        };
    }

    public static List<Class<?>> runtimeClasses() {
        final ArrayList<Class<?>> classes = new ArrayList<>();
        classes.add(EldArray.class);
        classes.add(EldTuple.class);
        classes.add(Introspection.class);
        classes.add(EldPromise.class);
        classes.add(EldApi.class);
        classes.add(EldPromise.State.class);
        classes.add(EldPromise.Continuation.class);
        classes.add(EldScheduler.class);
        classes.add(PromiseSource.class);
        for (final ArrayKind array : ArrayKind.values()) {
            classes.add(array.runtimeClass);
        }
        return classes;
    }

    private RuntimeAbi() {}
}
