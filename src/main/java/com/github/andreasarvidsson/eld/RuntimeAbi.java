package com.github.andreasarvidsson.eld;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import java.lang.classfile.TypeKind;
import com.github.andreasarvidsson.eld.semantic.BuiltinType;
import com.github.andreasarvidsson.eld.semantic.Type;
import com.github.andreasarvidsson.eld.runtime.EldArray;
import com.github.andreasarvidsson.eld.runtime.EldByteArray;
import com.github.andreasarvidsson.eld.runtime.EldShortArray;
import com.github.andreasarvidsson.eld.runtime.EldTuple;
import com.github.andreasarvidsson.eld.runtime.EldIntArray;
import com.github.andreasarvidsson.eld.runtime.EldLongArray;
import com.github.andreasarvidsson.eld.runtime.EldFloatArray;
import com.github.andreasarvidsson.eld.runtime.EldDoubleArray;
import com.github.andreasarvidsson.eld.runtime.EldBooleanArray;
import com.github.andreasarvidsson.eld.runtime.EldCharArray;
import com.github.andreasarvidsson.eld.runtime.EldObjectArray;

final class RuntimeAbi {
    static final String EMPTY_ARRAY_CONSTRUCTOR = "()V";

    enum ArrayKind {
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

        final @Nullable BuiltinType element;
        final Class<?> runtimeClass;
        final String owner;
        final String descriptor;
        final String elementDescriptor;
        final String backingDescriptor;
        final String constructorDescriptor;
        final TypeKind creationKind;

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

        String methodDescriptor(final ArrayMethod method) {
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

    static ArrayKind array(final Type element) {
        if (!(element instanceof BuiltinType builtin)) {
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

    static List<Class<?>> runtimeClasses() {
        final ArrayList<Class<?>> classes = new ArrayList<>();
        classes.add(EldArray.class);
        classes.add(EldTuple.class);
        for (final ArrayKind array : ArrayKind.values()) {
            classes.add(array.runtimeClass);
        }
        return classes;
    }

    private RuntimeAbi() {}
}
