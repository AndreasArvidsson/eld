package mylang;

import mylang.runtime.EldIntArray;

final class RuntimeAbi {
    static final Class<?> INT_ARRAY_CLASS = EldIntArray.class;
    static final String INT_ARRAY_OWNER =
        INT_ARRAY_CLASS.getName().replace('.', '/');
    static final String INT_ARRAY_DESCRIPTOR = "L" + INT_ARRAY_OWNER + ";";
    static final String INT_ARRAY_CONSTRUCTOR = "([I)V";
    static final String INT_ARRAY_EMPTY_CONSTRUCTOR = "()V";

    enum IntArrayMethod {
        SIZE("size", "()I"),
        GET("get", "(I)I"),
        SET("set", "(II)V"),
        COPY("copy", "()" + INT_ARRAY_DESCRIPTOR),
        SLICE_FROM("sliceFrom", "(I)" + INT_ARRAY_DESCRIPTOR),
        SLICE_TO("sliceTo", "(I)" + INT_ARRAY_DESCRIPTOR),
        SLICE("slice", "(II)" + INT_ARRAY_DESCRIPTOR);

        final String methodName;
        final String descriptor;

        IntArrayMethod(final String methodName, final String descriptor) {
            this.methodName = methodName;
            this.descriptor = descriptor;
        }
    }

    private RuntimeAbi() {}
}
