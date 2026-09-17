package com.github.andreasarvidsson.eld;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import com.github.andreasarvidsson.eld.semantic.BuiltinType;
import com.github.andreasarvidsson.eld.semantic.Type;
import com.github.andreasarvidsson.eld.runtime.EldArray;
import com.github.andreasarvidsson.eld.runtime.EldByteArray;
import com.github.andreasarvidsson.eld.runtime.EldShortArray;
import com.github.andreasarvidsson.eld.runtime.EldIntArray;
import com.github.andreasarvidsson.eld.runtime.EldLongArray;
import com.github.andreasarvidsson.eld.runtime.EldFloatArray;
import com.github.andreasarvidsson.eld.runtime.EldDoubleArray;
import com.github.andreasarvidsson.eld.runtime.EldBooleanArray;
import com.github.andreasarvidsson.eld.runtime.EldCharArray;

final class RuntimeAbi {
    static final String EMPTY_ARRAY_CONSTRUCTOR = "()V";

    enum PrimitiveArray {
        BYTE(BuiltinType.I8, EldByteArray.class, "B", Opcodes.T_BYTE),
        SHORT(BuiltinType.I16, EldShortArray.class, "S", Opcodes.T_SHORT),
        INT(BuiltinType.I32, EldIntArray.class, "I", Opcodes.T_INT),
        LONG(BuiltinType.I64, EldLongArray.class, "J", Opcodes.T_LONG),
        FLOAT(BuiltinType.F32, EldFloatArray.class, "F", Opcodes.T_FLOAT),
        DOUBLE(BuiltinType.F64, EldDoubleArray.class, "D", Opcodes.T_DOUBLE),
        BOOLEAN(
            BuiltinType.BOOL,
            EldBooleanArray.class,
            "Z",
            Opcodes.T_BOOLEAN
        ),
        CHAR(BuiltinType.CHAR, EldCharArray.class, "C", Opcodes.T_CHAR);

        final BuiltinType element;
        final Class<? extends EldArray<?>> runtimeClass;
        final String owner;
        final String descriptor;
        final String primitiveDescriptor;
        final String backingDescriptor;
        final String constructorDescriptor;
        final int creationOpcode;

        PrimitiveArray(
            final BuiltinType element,
            final Class<? extends EldArray<?>> runtimeClass,
            final String primitiveDescriptor,
            final int creationOpcode
        ) {
            this.element = element;
            this.runtimeClass = runtimeClass;
            this.owner = runtimeClass.getName().replace('.', '/');
            this.descriptor = "L" + owner + ";";
            this.primitiveDescriptor = primitiveDescriptor;
            this.backingDescriptor = "[" + primitiveDescriptor;
            this.constructorDescriptor = "(" + backingDescriptor + ")V";
            this.creationOpcode = creationOpcode;
        }

        String methodDescriptor(final ArrayMethod method) {
            return switch (method) {
                case SIZE -> "()I";
                case GET -> "(I)" + primitiveDescriptor;
                case SET -> "(I" + primitiveDescriptor + ")V";
                case ADD -> "(" + primitiveDescriptor + ")V";
                case COPY -> "()" + descriptor;
                case SLICE_FROM, SLICE_TO -> "(I)" + descriptor;
                case SLICE -> "(II)" + descriptor;
            };
        }
    }

    enum ArrayMethod {
        SIZE("size"),
        GET("get"),
        SET("set"),
        ADD("add"),
        COPY("copy"),
        SLICE_FROM("sliceFrom"),
        SLICE_TO("sliceTo"),
        SLICE("slice");

        final String methodName;

        ArrayMethod(final String methodName) {
            this.methodName = methodName;
        }

    }

    static @Nullable PrimitiveArray primitiveArray(final Type element) {
        if (!(element instanceof BuiltinType builtin)) {
            return null;
        }
        return switch (builtin) {
            case I8 -> PrimitiveArray.BYTE;
            case I16 -> PrimitiveArray.SHORT;
            case I32 -> PrimitiveArray.INT;
            case I64 -> PrimitiveArray.LONG;
            case F32 -> PrimitiveArray.FLOAT;
            case F64 -> PrimitiveArray.DOUBLE;
            case BOOL -> PrimitiveArray.BOOLEAN;
            case CHAR -> PrimitiveArray.CHAR;
            default -> null;
        };
    }

    static PrimitiveArray requirePrimitiveArray(final Type element) {
        return Objects.requireNonNull(primitiveArray(element));
    }

    static List<Class<?>> runtimeClasses() {
        final ArrayList<Class<?>> classes = new ArrayList<>();
        classes.add(EldArray.class);
        for (final PrimitiveArray array : PrimitiveArray.values()) {
            classes.add(array.runtimeClass);
        }
        return List.copyOf(classes);
    }

    private RuntimeAbi() {}
}
